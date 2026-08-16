package io.github.char0708.mch.platform.profile;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.char0708.mch.platform.audit.AuditService;
import io.github.char0708.mch.platform.identity.NaturalPersonActor;
import io.github.char0708.mch.platform.shared.ApiException;
import io.github.char0708.mch.platform.shared.BusinessTimes;
import io.github.char0708.mch.platform.shared.Hashing;
import io.github.char0708.mch.platform.shared.IdempotencyService;
import io.github.char0708.mch.platform.shared.Identifiers;

@Service
public class ProfileService {

	public static final String ADMITTED_STAGE = "PREGNANCY_SECOND_TRIMESTER";
	public static final String ADMITTED_SERVICE_MODE = "PREGNANCY";
	private static final String ENDPOINT = "POST:/api/v1/me/health-subjects/self";
	private static final Pattern SYNTHETIC_ALIAS = Pattern.compile("SYNTHETIC_[A-Z0-9_-]{1,24}");

	private final JdbcClient jdbcClient;
	private final IdempotencyService idempotencyService;
	private final AuditService auditService;

	ProfileService(JdbcClient jdbcClient, IdempotencyService idempotencyService, AuditService auditService) {
		this.jdbcClient = jdbcClient;
		this.idempotencyService = idempotencyService;
		this.auditService = auditService;
	}

	@Transactional
	public ProfileView createSelfProfile(NaturalPersonActor actor, ProfileCommand command, String idempotencyKey,
			String requestId) {
		String key = idempotencyService.requireValidKey(idempotencyKey);
		Instant receivedAt = Instant.now();
		validateRelationBoundary(command);
		validateProfile(command);
		Instant stageTime = BusinessTimes.parseNotFuture(command.stageBusinessTime(), "stageBusinessTime", receivedAt);
		LocalDate referenceDate = receivedAt.atZone(PregnancyDatingCalculator.BUSINESS_ZONE).toLocalDate();
		LocalDate lastMenstrualPeriodDate = parseRequiredDate(command.lastMenstrualPeriodDate(),
				"lastMenstrualPeriodDate");
		if (lastMenstrualPeriodDate.isAfter(referenceDate)) {
			throw invalid("lastMenstrualPeriodDate", "FUTURE_NOT_ALLOWED");
		}
		LocalDate clinicianEstimatedDueDate = parseOptionalDate(command.clinicianEstimatedDueDate(),
				"clinicianEstimatedDueDate");
		String payloadHash = Hashing.sha256(String.join("|", command.subjectType(), command.syntheticAlias(),
				command.serviceMode(), command.stageCode(), stageTime.toString(), lastMenstrualPeriodDate.toString(),
				Integer.toString(command.averageMenstrualDurationDays()),
				clinicianEstimatedDueDate == null ? "" : clinicianEstimatedDueDate.toString(),
				Boolean.toString(command.synthetic())));
		Optional<IdempotencyService.Replay> replay = idempotencyService.find(actorScope(actor.personId()), ENDPOINT, key, payloadHash);
		if (replay.isPresent()) {
			return findBySubjectId(replay.get().resourceId()).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
					"IDEMPOTENCY_KEY_REUSED", "幂等结果不可用。"));
		}
		PregnancyDatingCalculator.DatingResult dating = PregnancyDatingCalculator.calculate(referenceDate,
				lastMenstrualPeriodDate, clinicianEstimatedDueDate);
		if (!dating.isInsideR1Slice()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "GESTATIONAL_AGE_OUT_OF_R1_RANGE",
					"当前仅支持20周+0天至20周+6天的服务。",
					java.util.List.of(new ApiException.FieldError("estimatedDueDate", "R1_SEVEN_DAY_SLICE_REQUIRED")));
		}
		jdbcClient.sql("SELECT person_id FROM natural_person WHERE person_id = :personId AND status = 'ACTIVE' FOR UPDATE")
				.param("personId", actor.personId())
				.query(String.class)
				.single();
		if (findForPerson(actor.personId()).isPresent()) {
			throw new ApiException(HttpStatus.CONFLICT, "SELF_PROFILE_ALREADY_EXISTS", "本人档案已存在。");
		}

		String subjectId = Identifiers.newId();
		String relationId = Identifiers.newId();
		jdbcClient.sql("""
				INSERT INTO health_subject
				(subject_id, subject_type, synthetic_alias, validity_status, synthetic, created_at)
				VALUES (:subjectId, 'PREGNANT_WOMAN', :syntheticAlias, 'ACTIVE', TRUE, :createdAt)
				""")
				.param("subjectId", subjectId)
				.param("syntheticAlias", command.syntheticAlias())
				.param("createdAt", Timestamp.from(receivedAt))
				.update();
		jdbcClient.sql("""
				INSERT INTO person_subject_relation
				(relation_id, person_id, subject_id, relation_type, status, valid_from, valid_to, created_at)
				VALUES (:relationId, :personId, :subjectId, 'SELF', 'ACTIVE', :validFrom, NULL, :createdAt)
				""")
				.param("relationId", relationId)
				.param("personId", actor.personId())
				.param("subjectId", subjectId)
				.param("validFrom", Timestamp.from(receivedAt))
				.param("createdAt", Timestamp.from(receivedAt))
				.update();
		jdbcClient.sql("""
				INSERT INTO explicit_stage_profile
				(subject_id, service_mode, stage_code, stage_business_time, stage_source_type,
				 last_menstrual_period_date, average_menstrual_duration_days, clinician_estimated_due_date,
				 estimated_due_date, estimated_due_date_source, gestational_age_as_of_date,
				 gestational_age_weeks, gestational_age_days, validity_status, created_at)
				VALUES (:subjectId, :serviceMode, :stageCode, :stageBusinessTime, 'USER_SELF_REPORTED',
				 :lastMenstrualPeriodDate, :averageMenstrualDurationDays, :clinicianEstimatedDueDate,
				 :estimatedDueDate, :estimatedDueDateSource, :gestationalAgeAsOfDate,
				 :gestationalAgeWeeks, :gestationalAgeDays, 'ACTIVE', :createdAt)
				""")
				.param("subjectId", subjectId)
				.param("serviceMode", command.serviceMode())
				.param("stageCode", command.stageCode())
				.param("stageBusinessTime", Timestamp.from(stageTime))
				.param("lastMenstrualPeriodDate", java.sql.Date.valueOf(lastMenstrualPeriodDate))
				.param("averageMenstrualDurationDays", command.averageMenstrualDurationDays())
				.param("clinicianEstimatedDueDate", clinicianEstimatedDueDate == null
						? null : java.sql.Date.valueOf(clinicianEstimatedDueDate))
				.param("estimatedDueDate", java.sql.Date.valueOf(dating.estimatedDueDate()))
				.param("estimatedDueDateSource", dating.source())
				.param("gestationalAgeAsOfDate", java.sql.Date.valueOf(dating.asOfDate()))
				.param("gestationalAgeWeeks", dating.weeks())
				.param("gestationalAgeDays", dating.days())
				.param("createdAt", Timestamp.from(receivedAt))
				.update();
		jdbcClient.sql("UPDATE natural_person SET profile_status = 'COMPLETE', updated_at = :updatedAt WHERE person_id = :personId")
				.param("personId", actor.personId())
				.param("updatedAt", Timestamp.from(receivedAt))
				.update();
		idempotencyService.record(actorScope(actor.personId()), ENDPOINT, key, payloadHash, "HEALTH_SUBJECT", subjectId, 201, receivedAt);
		auditService.success("SELF_PROFILE_CREATED", actor.personId(), NaturalPersonActor.PRINCIPAL_TYPE, "CREATE_SELF_PROFILE",
				"HEALTH_SUBJECT", subjectId, "REQUIRED", "COMPLETE", null, null, requestId);
		return findBySubjectId(subjectId).orElseThrow();
	}

	public ProfileView requireProfile(String personId) {
		return findForPerson(personId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PROFILE_REQUIRED",
				"请先完成本人档案。"));
	}

	public void requireOwnSubject(String personId, String subjectId) {
		Integer count = jdbcClient.sql("""
				SELECT COUNT(*) FROM person_subject_relation
				WHERE person_id = :personId AND subject_id = :subjectId AND relation_type = 'SELF'
				  AND status = 'ACTIVE' AND valid_to IS NULL
				""")
				.param("personId", personId)
				.param("subjectId", subjectId)
				.query(Integer.class)
				.single();
		if (count != 1) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问该健康主体。");
		}
	}

	public Optional<ProfileView> findForPerson(String personId) {
		return jdbcClient.sql(baseQuery() + " WHERE r.person_id = :personId AND r.relation_type = 'SELF' AND r.status = 'ACTIVE'")
				.param("personId", personId)
				.query((resultSet, rowNumber) -> mapProfile(resultSet))
				.optional();
	}

	private Optional<ProfileView> findBySubjectId(String subjectId) {
		return jdbcClient.sql(baseQuery() + " WHERE s.subject_id = :subjectId AND r.relation_type = 'SELF' AND r.status = 'ACTIVE'")
				.param("subjectId", subjectId)
				.query((resultSet, rowNumber) -> mapProfile(resultSet))
				.optional();
	}

	private static String baseQuery() {
		return """
				SELECT s.subject_id, s.subject_type, s.synthetic_alias, s.validity_status, s.synthetic, s.created_at,
				       r.relation_id, r.relation_type, r.status AS relation_status, r.valid_from,
				       p.service_mode, p.stage_code, p.stage_business_time, p.stage_source_type,
				       p.last_menstrual_period_date, p.average_menstrual_duration_days,
				       p.clinician_estimated_due_date, p.estimated_due_date, p.estimated_due_date_source,
				       p.gestational_age_as_of_date, p.gestational_age_weeks, p.gestational_age_days
				FROM health_subject s
				JOIN person_subject_relation r ON r.subject_id = s.subject_id
				JOIN explicit_stage_profile p ON p.subject_id = s.subject_id
				""";
	}

	private static ProfileView mapProfile(java.sql.ResultSet resultSet) throws java.sql.SQLException {
		LocalDate clinicianEstimatedDueDate = resultSet.getObject("clinician_estimated_due_date", LocalDate.class);
		Integer gestationalAgeWeeks = resultSet.getObject("gestational_age_weeks", Integer.class);
		Integer gestationalAgeDays = resultSet.getObject("gestational_age_days", Integer.class);
		return new ProfileView(resultSet.getString("subject_id"), resultSet.getString("subject_type"),
				resultSet.getString("synthetic_alias"), resultSet.getString("service_mode"), resultSet.getString("stage_code"),
				resultSet.getTimestamp("stage_business_time").toInstant().toString(),
				resultSet.getString("stage_source_type"),
				toIsoDate(resultSet, "last_menstrual_period_date"), resultSet.getInt("average_menstrual_duration_days"),
				clinicianEstimatedDueDate == null ? null : clinicianEstimatedDueDate.toString(),
				toIsoDate(resultSet, "estimated_due_date"), resultSet.getString("estimated_due_date_source"),
				toIsoDate(resultSet, "gestational_age_as_of_date"), gestationalAgeWeeks, gestationalAgeDays,
				gestationalAgeWeeks == null || gestationalAgeDays == null
						? null : gestationalAgeWeeks + "周+" + gestationalAgeDays + "天",
				resultSet.getString("validity_status"),
				resultSet.getBoolean("synthetic"), resultSet.getTimestamp("created_at").toInstant().toString(),
				new RelationView(resultSet.getString("relation_id"), resultSet.getString("relation_type"),
						resultSet.getString("relation_status"), resultSet.getTimestamp("valid_from").toInstant().toString()));
	}

	private static void validateRelationBoundary(ProfileCommand command) {
		if ("CHILD".equals(command.subjectType()) || command.relationType() != null || command.subjectRelationshipType() != null) {
			throw new ApiException(HttpStatus.CONFLICT, "RELATION_FLOW_NOT_ADMITTED",
					"儿童、亲情代办和主体关系流程尚未准入。");
		}
	}

	private static void validateProfile(ProfileCommand command) {
		if (!command.synthetic()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SYNTHETIC_ONLY_VIOLATION", "仅允许纯合成档案。");
		}
		if (!"PREGNANT_WOMAN".equals(command.subjectType())) {
			throw invalid("subjectType", "ONLY_PREGNANT_WOMAN_ADMITTED");
		}
		if (command.syntheticAlias() == null || !SYNTHETIC_ALIAS.matcher(command.syntheticAlias()).matches()) {
			throw invalid("syntheticAlias", "SYNTHETIC_ALIAS_REQUIRED");
		}
		if (!ADMITTED_SERVICE_MODE.equals(command.serviceMode())) {
			throw invalid("serviceMode", "ONLY_PREGNANCY_ADMITTED");
		}
		if (!ADMITTED_STAGE.equals(command.stageCode())) {
			throw invalid("stageCode", "ONLY_EXPLICIT_SECOND_TRIMESTER_ADMITTED");
		}
		if (command.averageMenstrualDurationDays() == null
				|| command.averageMenstrualDurationDays() < 3
				|| command.averageMenstrualDurationDays() > 15) {
			throw invalid("averageMenstrualDurationDays", "INTEGER_3_TO_15_REQUIRED");
		}
	}

	private static LocalDate parseRequiredDate(String raw, String field) {
		if (raw == null || !raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
			throw invalid(field, "ISO_LOCAL_DATE_REQUIRED");
		}
		try {
			return LocalDate.parse(raw);
		}
		catch (DateTimeParseException exception) {
			throw invalid(field, "ISO_LOCAL_DATE_REQUIRED");
		}
	}

	private static LocalDate parseOptionalDate(String raw, String field) {
		if (raw == null) {
			return null;
		}
		return parseRequiredDate(raw, field);
	}

	private static String toIsoDate(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
		LocalDate value = resultSet.getObject(column, LocalDate.class);
		return value == null ? null : value.toString();
	}

	private static ApiException invalid(String field, String reason) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "本人合成档案字段无效。",
				java.util.List.of(new ApiException.FieldError(field, reason)));
	}

	private static String actorScope(String personId) {
		return "NATURAL_PERSON:" + personId;
	}

	public record ProfileCommand(String subjectType, String syntheticAlias, String serviceMode, String stageCode,
			String stageBusinessTime, String lastMenstrualPeriodDate, Integer averageMenstrualDurationDays,
			String clinicianEstimatedDueDate, boolean synthetic, String relationType, String subjectRelationshipType) {
	}

	public record ProfileView(String subjectId, String subjectType, String syntheticAlias, String serviceMode,
			String stageCode, String stageBusinessTime, String stageSourceType, String lastMenstrualPeriodDate,
			int averageMenstrualDurationDays, String clinicianEstimatedDueDate, String estimatedDueDate,
			String estimatedDueDateSource, String gestationalAgeAsOfDate, Integer gestationalAgeWeeks,
			Integer gestationalAgeDays, String gestationalAgeDisplay, String validityStatus, boolean synthetic,
			String createdAt, RelationView relation) {
	}

	public record RelationView(String relationId, String relationType, String status, String validFrom) {
	}
}
