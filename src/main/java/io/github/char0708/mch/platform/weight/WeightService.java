package io.github.char0708.mch.platform.weight;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.char0708.mch.platform.audit.AuditService;
import io.github.char0708.mch.platform.identity.NaturalPersonActor;
import io.github.char0708.mch.platform.profile.ProfileService;
import io.github.char0708.mch.platform.shared.ApiException;
import io.github.char0708.mch.platform.shared.BusinessTimes;
import io.github.char0708.mch.platform.shared.Hashing;
import io.github.char0708.mch.platform.shared.IdempotencyService;
import io.github.char0708.mch.platform.shared.Identifiers;

@Service
public class WeightService {

	private static final Pattern VALUE = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{1,2})?");
	private static final Pattern HEIGHT = Pattern.compile("[0-9]{2,3}(?:\\.[0-9]{1,2})?");
	private static final Set<String> MOOD_CODES = Set.of("HAPPY", "CALM", "LOW", "DISTRESSED");
	private static final String MANAGEMENT_ADVICE =
			"体重变化需要结合孕前情况和专业建议综合判断。保持规律记录，比只关注某一次数值更有参考意义。";

	private final JdbcClient jdbcClient;
	private final ProfileService profileService;
	private final IdempotencyService idempotencyService;
	private final AuditService auditService;

	WeightService(JdbcClient jdbcClient, ProfileService profileService, IdempotencyService idempotencyService,
			AuditService auditService) {
		this.jdbcClient = jdbcClient;
		this.profileService = profileService;
		this.idempotencyService = idempotencyService;
		this.auditService = auditService;
	}

	@Transactional
	public WeightView record(NaturalPersonActor actor, String subjectId, WeightCommand command,
			String idempotencyKey, String requestId) {
		profileService.requireOwnSubject(actor.personId(), subjectId);
		String key = idempotencyService.requireValidKey(idempotencyKey);
		Instant receivedAt = Instant.now();
		BigDecimal value = validatePositiveDecimal(command.value(), "value");
		validateFixedFields(command);
		WeightManagementBaselineInput baseline = validateBaseline(command);
		String moodCode = validateMoodCode(command.moodCode());
		Instant businessTime = BusinessTimes.parseNotFuture(command.businessTime(), "businessTime", receivedAt);
		String normalizedValue = value.stripTrailingZeros().toPlainString();
		String payloadHash = Hashing.sha256(String.join("|", subjectId, normalizedValue, "KG",
				businessTime.toString(), "USER_SELF_REPORTED", "true",
				baseline == null ? "" : baseline.prePregnancyWeightKg().stripTrailingZeros().toPlainString(),
				baseline == null ? "" : baseline.heightCm().stripTrailingZeros().toPlainString(),
				moodCode == null ? "" : moodCode));
		String endpoint = "POST:/api/v1/me/health-subjects/" + subjectId + "/weights";
		Optional<IdempotencyService.Replay> replay = idempotencyService.find(actorScope(actor.personId()), endpoint, key, payloadHash);
		if (replay.isPresent()) {
			return findById(replay.get().resourceId()).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
					"IDEMPOTENCY_KEY_REUSED", "幂等结果不可用。"));
		}

		String semanticFingerprint = Hashing.sha256(String.join("|", subjectId, "WEIGHT", normalizedValue,
				"KG", businessTime.toString()));
		Optional<WeightView> duplicate = findByFingerprint(semanticFingerprint);
		if (duplicate.isPresent()) {
			throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_WEIGHT_RECORD", "相同业务事实的体重记录已存在。");
		}

		String recordId = Identifiers.newId();
		try {
			if (baseline != null) {
				upsertBaseline(subjectId, baseline, receivedAt);
			}
			jdbcClient.sql("""
					INSERT INTO weight_record
					(record_id, subject_id, record_type, value_text, value_decimal, unit, business_time,
					 source_type, mood_code, validity_status, semantic_fingerprint, created_at)
					VALUES (:recordId, :subjectId, 'WEIGHT', :valueText, :valueDecimal, 'KG', :businessTime,
					 'USER_SELF_REPORTED', :moodCode, 'ACTIVE', :semanticFingerprint, :createdAt)
					""")
					.param("recordId", recordId)
					.param("subjectId", subjectId)
					.param("valueText", command.value())
					.param("valueDecimal", value)
					.param("businessTime", Timestamp.from(businessTime))
					.param("moodCode", moodCode)
					.param("semanticFingerprint", semanticFingerprint)
					.param("createdAt", Timestamp.from(receivedAt))
					.update();
		}
		catch (DuplicateKeyException exception) {
			throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_WEIGHT_RECORD", "相同业务事实的体重记录已存在。");
		}
		idempotencyService.record(actorScope(actor.personId()), endpoint, key, payloadHash, "WEIGHT_RECORD", recordId, 201, receivedAt);
		auditService.success("WEIGHT_RECORDED", actor.personId(), NaturalPersonActor.PRINCIPAL_TYPE, "RECORD_WEIGHT",
				"WEIGHT_RECORD", recordId, null, "ACTIVE", null, null, requestId);
		return findById(recordId).orElseThrow();
	}

	public WeightTimeline timeline(String personId, String subjectId) {
		profileService.requireOwnSubject(personId, subjectId);
		ProfileService.ProfileView profile = profileService.requireProfile(personId);
		List<WeightView> records = jdbcClient.sql("""
				SELECT record_id, subject_id, value_text, unit, business_time, source_type, mood_code,
				       validity_status, created_at
				FROM weight_record WHERE subject_id = :subjectId
				ORDER BY business_time ASC, created_at ASC, record_id ASC
				""")
				.param("subjectId", subjectId)
				.query((resultSet, rowNumber) -> mapWeight(resultSet))
				.list();
		WeightView latest = records.stream().max(java.util.Comparator
				.comparing((WeightView view) -> Instant.parse(view.businessTime()))
				.thenComparing(view -> Instant.parse(view.createdAt()))
				.thenComparing(WeightView::recordId)).orElse(null);
		WeightManagementBaseline baseline = findBaseline(subjectId).orElse(null);
		WeightManagementAdvice advice = new WeightManagementAdvice(profile.stageCode(), "体重管理建议",
				MANAGEMENT_ADVICE, false);
		return new WeightTimeline(records, records, latest, baseline, advice);
	}

	@Transactional
	public WeightManagementBaseline saveBaseline(NaturalPersonActor actor, String subjectId,
			WeightManagementBaselineCommand command, String requestId) {
		profileService.requireOwnSubject(actor.personId(), subjectId);
		WeightManagementBaselineInput baseline = validateBaseline(command.prePregnancyWeightKg(), command.heightCm());
		Instant updatedAt = Instant.now();
		upsertBaseline(subjectId, baseline, updatedAt);
		auditService.success("WEIGHT_BASELINE_SAVED", actor.personId(), NaturalPersonActor.PRINCIPAL_TYPE,
				"SAVE_WEIGHT_BASELINE", "HEALTH_SUBJECT", subjectId, null, "ACTIVE", null, null, requestId);
		return findBaseline(subjectId).orElseThrow();
	}

	private void upsertBaseline(String subjectId, WeightManagementBaselineInput baseline, Instant updatedAt) {
		jdbcClient.sql("""
				INSERT INTO weight_management_baseline
				(subject_id, pre_pregnancy_weight_kg, height_cm, updated_at)
				VALUES (:subjectId, :prePregnancyWeightKg, :heightCm, :updatedAt)
				ON DUPLICATE KEY UPDATE
				pre_pregnancy_weight_kg = VALUES(pre_pregnancy_weight_kg),
				height_cm = VALUES(height_cm),
				updated_at = VALUES(updated_at)
				""")
				.param("subjectId", subjectId)
				.param("prePregnancyWeightKg", baseline.prePregnancyWeightKg())
				.param("heightCm", baseline.heightCm())
				.param("updatedAt", Timestamp.from(updatedAt))
				.update();
	}

	private Optional<WeightManagementBaseline> findBaseline(String subjectId) {
		return jdbcClient.sql("""
				SELECT pre_pregnancy_weight_kg, height_cm, updated_at
				FROM weight_management_baseline
				WHERE subject_id = :subjectId
				""")
				.param("subjectId", subjectId)
				.query((resultSet, rowNumber) -> {
					BigDecimal prePregnancyWeightKg = resultSet.getBigDecimal("pre_pregnancy_weight_kg");
					BigDecimal heightCm = resultSet.getBigDecimal("height_cm");
					BigDecimal heightMeters = heightCm.movePointLeft(2);
					BigDecimal bmi = prePregnancyWeightKg.divide(heightMeters.multiply(heightMeters), 1,
							RoundingMode.HALF_UP);
					return new WeightManagementBaseline(displayDecimal(prePregnancyWeightKg),
							displayDecimal(heightCm), bmi.toPlainString(),
							resultSet.getTimestamp("updated_at").toInstant().toString());
				})
				.optional();
	}

	private Optional<WeightView> findById(String recordId) {
		return jdbcClient.sql(selectWeight() + " WHERE record_id = :recordId")
				.param("recordId", recordId)
				.query((resultSet, rowNumber) -> mapWeight(resultSet))
				.optional();
	}

	private Optional<WeightView> findByFingerprint(String fingerprint) {
		return jdbcClient.sql(selectWeight() + " WHERE semantic_fingerprint = :fingerprint")
				.param("fingerprint", fingerprint)
				.query((resultSet, rowNumber) -> mapWeight(resultSet))
				.optional();
	}

	private static String selectWeight() {
		return """
				SELECT record_id, subject_id, value_text, unit, business_time, source_type, mood_code,
				       validity_status, created_at
				FROM weight_record
				""";
	}

	private static WeightView mapWeight(java.sql.ResultSet resultSet) throws java.sql.SQLException {
		return new WeightView(resultSet.getString("record_id"), resultSet.getString("subject_id"), "WEIGHT",
				resultSet.getString("value_text"), resultSet.getString("unit"),
				resultSet.getTimestamp("business_time").toInstant().toString(), resultSet.getString("source_type"),
				resultSet.getString("mood_code"),
				resultSet.getString("validity_status"), resultSet.getTimestamp("created_at").toInstant().toString());
	}

	private static WeightManagementBaselineInput validateBaseline(WeightCommand command) {
		boolean hasWeight = command.prePregnancyWeightKg() != null && !command.prePregnancyWeightKg().isBlank();
		boolean hasHeight = command.heightCm() != null && !command.heightCm().isBlank();
		if (hasWeight != hasHeight) {
			throw invalid(hasWeight ? "heightCm" : "prePregnancyWeightKg", "BASELINE_PAIR_REQUIRED");
		}
		if (!hasWeight) {
			return null;
		}
		return validateBaseline(command.prePregnancyWeightKg(), command.heightCm());
	}

	private static WeightManagementBaselineInput validateBaseline(String prePregnancyWeightRaw, String heightRaw) {
		if (prePregnancyWeightRaw == null || prePregnancyWeightRaw.isBlank()
				|| heightRaw == null || heightRaw.isBlank()) {
			throw invalid(prePregnancyWeightRaw == null || prePregnancyWeightRaw.isBlank()
					? "prePregnancyWeightKg" : "heightCm", "BASELINE_PAIR_REQUIRED");
		}
		BigDecimal prePregnancyWeightKg = validatePositiveDecimal(prePregnancyWeightRaw, "prePregnancyWeightKg");
		if (!HEIGHT.matcher(heightRaw).matches()) {
			throw invalid("heightCm", "POSITIVE_DECIMAL_MAX_3_2");
		}
		BigDecimal heightCm = new BigDecimal(heightRaw);
		if (heightCm.compareTo(BigDecimal.valueOf(50)) < 0 || heightCm.compareTo(BigDecimal.valueOf(250)) > 0) {
			throw invalid("heightCm", "MUST_BE_BETWEEN_50_AND_250");
		}
		return new WeightManagementBaselineInput(prePregnancyWeightKg, heightCm);
	}

	private static String validateMoodCode(String moodCode) {
		if (moodCode == null || moodCode.isBlank()) {
			return null;
		}
		if (!MOOD_CODES.contains(moodCode)) {
			throw invalid("moodCode", "UNSUPPORTED_MOOD_CODE");
		}
		return moodCode;
	}

	private static String displayDecimal(BigDecimal value) {
		BigDecimal normalized = value.stripTrailingZeros();
		return normalized.scale() <= 0 ? normalized.setScale(1).toPlainString() : normalized.toPlainString();
	}

	private static BigDecimal validatePositiveDecimal(String raw, String field) {
		if (raw == null || !VALUE.matcher(raw).matches()) {
			throw invalid(field, "POSITIVE_DECIMAL_MAX_3_2");
		}
		BigDecimal value = new BigDecimal(raw);
		if (value.signum() <= 0) {
			throw invalid(field, "MUST_BE_GREATER_THAN_ZERO");
		}
		return value;
	}

	private static void validateFixedFields(WeightCommand command) {
		if (!"KG".equals(command.unit())) {
			throw invalid("unit", "ONLY_KG_ADMITTED");
		}
		if (!"USER_SELF_REPORTED".equals(command.sourceType())) {
			throw invalid("sourceType", "ONLY_USER_SELF_REPORTED_ADMITTED");
		}
		if (!command.synthetic()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SYNTHETIC_ONLY_VIOLATION", "仅允许纯合成体重记录。");
		}
	}

	private static ApiException invalid(String field, String reason) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "体重字段无效。",
				java.util.List.of(new ApiException.FieldError(field, reason)));
	}

	private static String actorScope(String personId) {
		return "NATURAL_PERSON:" + personId;
	}

	public record WeightCommand(String value, String unit, String businessTime, String sourceType,
			String prePregnancyWeightKg, String heightCm, String moodCode, boolean synthetic) {
	}

	public record WeightManagementBaselineCommand(String prePregnancyWeightKg, String heightCm) {
	}

	public record WeightView(String recordId, String subjectId, String recordType, String value, String unit,
			String businessTime, String sourceType, String moodCode, String validityStatus, String createdAt) {
	}

	public record WeightManagementBaseline(String prePregnancyWeightKg, String heightCm, String bmi,
			String updatedAt) {
	}

	public record WeightManagementAdvice(String stageCode, String title, String body, boolean personalized) {
	}

	public record WeightTimeline(List<WeightView> records, List<WeightView> trend, WeightView latestWeight,
			WeightManagementBaseline baseline, WeightManagementAdvice managementAdvice) {
	}

	private record WeightManagementBaselineInput(BigDecimal prePregnancyWeightKg, BigDecimal heightCm) {
	}
}
