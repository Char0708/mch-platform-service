package io.github.char0708.mch.platform.content;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.char0708.mch.platform.audit.AuditService;
import io.github.char0708.mch.platform.auth.SessionActor;
import io.github.char0708.mch.platform.profile.ProfileService;
import io.github.char0708.mch.platform.shared.ApiException;
import io.github.char0708.mch.platform.shared.Hashing;
import io.github.char0708.mch.platform.shared.IdempotencyService;
import io.github.char0708.mch.platform.shared.Identifiers;

@Service
public class ContentService {

	public static final String SYNTHETIC_LABEL = "纯合成演示内容，不构成医疗建议";
	private static final Pattern DISALLOWED_MEDICAL_CLAIM = Pattern.compile(
			"诊断|治疗|处方|风险分级|个性化建议|正常范围|异常|diagnos|treat|prescri|risk score|personalized advice",
			Pattern.CASE_INSENSITIVE);
	private static final List<String> REVIEW_DECISIONS = List.of("APPROVE", "REJECT_SYNTHETIC_LABEL_MISSING",
			"REJECT_OUT_OF_SCOPE", "REJECT_REVISION_REQUIRED");

	private final JdbcClient jdbcClient;
	private final IdempotencyService idempotencyService;
	private final AuditService auditService;

	ContentService(JdbcClient jdbcClient, IdempotencyService idempotencyService, AuditService auditService) {
		this.jdbcClient = jdbcClient;
		this.idempotencyService = idempotencyService;
		this.auditService = auditService;
	}

	@Transactional
	public ContentVersionView create(SessionActor actor, CreateContentCommand command, String idempotencyKey,
			String requestId) {
		String key = idempotencyService.requireValidKey(idempotencyKey);
		validateEditableContent(command.title(), command.body(), command.targetStageCode(), command.contentBasis());
		validateSlot(command.slotCode());
		String payloadHash = Hashing.sha256(String.join("|", command.title(), command.body(),
				command.targetStageCode(), command.contentBasis(), nullable(command.slotCode()),
				nullable(command.summary()), nullable(command.sourceReference()), nullable(command.sourceReviewDate())));
		String endpoint = "POST:/api/v1/ops/contents";
		Optional<IdempotencyService.Replay> replay = idempotencyService.find(actor.accountId(), endpoint, key, payloadHash);
		if (replay.isPresent()) {
			return requireVersion(replay.get().resourceId());
		}
		Instant now = Instant.now();
		String contentId = Identifiers.newId();
		String versionId = Identifiers.newId();
		jdbcClient.sql("INSERT INTO content_item (content_id, current_published_version_id, created_at) VALUES (:contentId, NULL, :createdAt)")
				.param("contentId", contentId)
				.param("createdAt", Timestamp.from(now))
				.update();
		jdbcClient.sql("""
				INSERT INTO content_version
				(version_id, content_id, version_number, title, body, summary_text, target_stage_code, content_basis,
				 source_reference, source_review_date, synthetic_display_label, professional_review_status,
				 status, author_actor_id, revision, created_at, updated_at)
				VALUES (:versionId, :contentId, 1, :title, :body, :summary, :stage, 'SYNTHETIC_DEMO',
				 :sourceReference, :sourceReviewDate, :label, 'NOT_APPLICABLE_SYNTHETIC',
				 'DRAFT', :authorId, 1, :createdAt, :updatedAt)
				""")
				.param("versionId", versionId)
				.param("contentId", contentId)
				.param("title", command.title())
				.param("body", command.body())
				.param("summary", command.summary())
				.param("stage", command.targetStageCode())
				.param("sourceReference", command.sourceReference())
				.param("sourceReviewDate", parseDate(command.sourceReviewDate()))
				.param("label", SYNTHETIC_LABEL)
				.param("authorId", actor.accountId())
				.param("createdAt", Timestamp.from(now))
				.param("updatedAt", Timestamp.from(now))
				.update();
		if (command.slotCode() != null) {
			int bound = jdbcClient.sql("""
					UPDATE r1_content_slot SET content_id = :contentId
					WHERE slot_code = :slotCode AND content_id IS NULL
					""")
					.param("contentId", contentId)
					.param("slotCode", command.slotCode())
					.update();
			if (bound != 1) {
				throw new ApiException(HttpStatus.CONFLICT, "CONTENT_SLOT_OCCUPIED", "该内容槽位已存在内容。");
			}
		}
		idempotencyService.record(actor.accountId(), endpoint, key, payloadHash, "CONTENT_VERSION", versionId, 201, now);
		auditService.success("CONTENT_DRAFT_CREATED", actor.accountId(), actor.role().name(), "CREATE_CONTENT_DRAFT",
				"CONTENT_VERSION", versionId, null, "DRAFT", null, null, requestId);
		return requireVersion(versionId);
	}

	@Transactional
	public ContentVersionView createSuccessorDraft(SessionActor actor, String contentId,
			CreateSuccessorDraftCommand command, String requestId) {
		if (command.sourceVersionId() == null || command.sourceVersionId().isBlank()) {
			throw invalid("sourceVersionId", "REQUIRED");
		}
		ContentVersionView source = jdbcClient.sql(versionSelect() + """
				 WHERE content_id = :contentId AND version_id = :sourceVersionId FOR UPDATE
				""")
				.param("contentId", contentId)
				.param("sourceVersionId", command.sourceVersionId())
				.query((resultSet, rowNumber) -> mapVersion(resultSet))
				.optional()
				.orElseThrow(ContentService::sourceNotCurrent);
		String currentPublishedVersionId = jdbcClient.sql("""
				SELECT current_published_version_id FROM content_item
				WHERE content_id = :contentId FOR UPDATE
				""")
				.param("contentId", contentId)
				.query(String.class)
				.optional()
				.orElse(null);
		if (!source.versionId().equals(currentPublishedVersionId) || !"PUBLISHED".equals(source.status())) {
			throw sourceNotCurrent();
		}
		ContentVersionView existingSuccessor = jdbcClient.sql(versionSelect() + """
				 WHERE content_id = :contentId AND version_number > :sourceVersionNumber
				 ORDER BY version_number DESC LIMIT 1
				""")
				.param("contentId", contentId)
				.param("sourceVersionNumber", source.versionNumber())
				.query((resultSet, rowNumber) -> mapVersion(resultSet))
				.optional()
				.orElse(null);
		if (existingSuccessor != null) {
			if ("DRAFT".equals(existingSuccessor.status())) {
				return existingSuccessor;
			}
			throw revisionAlreadyInProgress();
		}

		Instant now = Instant.now();
		String successorVersionId = Identifiers.newId();
		jdbcClient.sql("""
				INSERT INTO content_version
				(version_id, content_id, version_number, title, body, summary_text, target_stage_code, content_basis,
				 source_reference, source_review_date, synthetic_display_label, professional_review_status,
				 status, author_actor_id, revision, created_at, updated_at)
				SELECT :successorVersionId, content_id, version_number + 1, title, body, summary_text,
				 target_stage_code, content_basis, source_reference, source_review_date, synthetic_display_label,
				 professional_review_status, 'DRAFT', :authorActorId, 1, :createdAt, :updatedAt
				FROM content_version WHERE version_id = :sourceVersionId
				""")
				.param("successorVersionId", successorVersionId)
				.param("authorActorId", actor.accountId())
				.param("createdAt", Timestamp.from(now))
				.param("updatedAt", Timestamp.from(now))
				.param("sourceVersionId", source.versionId())
				.update();
		auditService.success("CONTENT_SUCCESSOR_DRAFT_CREATED", actor.accountId(), actor.role().name(),
				"CREATE_CONTENT_SUCCESSOR_DRAFT", "CONTENT_VERSION", successorVersionId,
				"PUBLISHED", "DRAFT", null, null, requestId);
		return requireVersion(successorVersionId);
	}

	@Transactional
	public ContentVersionView update(SessionActor actor, String contentId, String versionId,
			UpdateContentCommand command, String ifMatch, String idempotencyKey, String requestId) {
		int expectedRevision = parseRevision(ifMatch);
		String key = idempotencyService.requireValidKey(idempotencyKey);
		validateEditableContent(command.title(), command.body(), command.targetStageCode(), command.contentBasis());
		String payloadHash = Hashing.sha256(String.join("|", contentId, versionId, command.title(), command.body(),
				command.targetStageCode(), command.contentBasis(), Integer.toString(expectedRevision),
				nullable(command.summary()), nullable(command.sourceReference()), nullable(command.sourceReviewDate())));
		String endpoint = "PUT:/api/v1/ops/contents/" + contentId + "/versions/" + versionId;
		Optional<IdempotencyService.Replay> replay = idempotencyService.find(actor.accountId(), endpoint, key, payloadHash);
		if (replay.isPresent()) {
			return requireVersion(replay.get().resourceId());
		}
		ContentVersionView current = requireVersionForContentForUpdate(contentId, versionId);
		requireAuthor(actor, current);
		if (!"DRAFT".equals(current.status())) {
			throw invalidState();
		}
		if (current.revision() != expectedRevision) {
			throw new ApiException(HttpStatus.CONFLICT, "CONTENT_VERSION_CONFLICT", "内容版本修订号已变化。");
		}
		Instant now = Instant.now();
		int updated = jdbcClient.sql("""
				UPDATE content_version SET title = :title, body = :body, summary_text = :summary,
				 target_stage_code = :stage, content_basis = 'SYNTHETIC_DEMO', source_reference = :sourceReference,
				 source_review_date = :sourceReviewDate, revision = revision + 1, updated_at = :updatedAt
				WHERE version_id = :versionId AND revision = :revision AND status = 'DRAFT'
				""")
				.param("title", command.title())
				.param("body", command.body())
				.param("summary", command.summary())
				.param("stage", command.targetStageCode())
				.param("sourceReference", command.sourceReference())
				.param("sourceReviewDate", parseDate(command.sourceReviewDate()))
				.param("updatedAt", Timestamp.from(now))
				.param("versionId", versionId)
				.param("revision", expectedRevision)
				.update();
		if (updated != 1) {
			throw new ApiException(HttpStatus.CONFLICT, "CONTENT_VERSION_CONFLICT", "内容版本修订号已变化。");
		}
		idempotencyService.record(actor.accountId(), endpoint, key, payloadHash, "CONTENT_VERSION", versionId, 200, now);
		auditService.success("CONTENT_DRAFT_UPDATED", actor.accountId(), actor.role().name(), "UPDATE_CONTENT_DRAFT",
				"CONTENT_VERSION", versionId, "DRAFT", "DRAFT", null, null, requestId);
		return requireVersion(versionId);
	}

	@Transactional
	public ContentVersionView submit(SessionActor actor, String contentId, String versionId,
			String idempotencyKey, String requestId) {
		String endpoint = operationEndpoint("submit", contentId, versionId);
		String key = idempotencyService.requireValidKey(idempotencyKey);
		String payloadHash = Hashing.sha256(contentId + "|" + versionId + "|submit");
		Optional<IdempotencyService.Replay> replay = idempotencyService.find(actor.accountId(), endpoint, key, payloadHash);
		if (replay.isPresent()) {
			return requireVersion(replay.get().resourceId());
		}
		ContentVersionView current = requireVersionForContentForUpdate(contentId, versionId);
		requireAuthor(actor, current);
		if (!"DRAFT".equals(current.status())) {
			throw invalidState();
		}
		ensureSyntheticReady(current);
		Instant now = Instant.now();
		jdbcClient.sql("UPDATE content_version SET status = 'IN_REVIEW', submitted_by_actor_id = :actorId, submitted_at = :now, updated_at = :now WHERE version_id = :versionId")
				.param("actorId", actor.accountId())
				.param("now", Timestamp.from(now))
				.param("versionId", versionId)
				.update();
		idempotencyService.record(actor.accountId(), endpoint, key, payloadHash, "CONTENT_VERSION", versionId, 200, now);
		auditService.success("CONTENT_SUBMITTED", actor.accountId(), actor.role().name(), "SUBMIT_CONTENT",
				"CONTENT_VERSION", versionId, "DRAFT", "IN_REVIEW", null, null, requestId);
		return requireVersion(versionId);
	}

	@Transactional
	public ContentVersionView review(SessionActor actor, String contentId, String versionId,
			ReviewCommand command, String idempotencyKey, String requestId) {
		if (command.decisionCode() == null || !REVIEW_DECISIONS.contains(command.decisionCode())) {
			throw invalid("decisionCode", "CONTROLLED_DECISION_REQUIRED");
		}
		String endpoint = operationEndpoint("review-decisions", contentId, versionId);
		String key = idempotencyService.requireValidKey(idempotencyKey);
		String payloadHash = Hashing.sha256(contentId + "|" + versionId + "|" + command.decisionCode());
		Optional<IdempotencyService.Replay> replay = idempotencyService.find(actor.accountId(), endpoint, key, payloadHash);
		if (replay.isPresent()) {
			return requireVersion(replay.get().resourceId());
		}
		ContentVersionView current = requireVersionForContentForUpdate(contentId, versionId);
		if (actor.accountId().equals(current.authorActorId())
				|| actor.accountId().equals(current.submittedByActorId())) {
			throw selfReview();
		}
		if (!"IN_REVIEW".equals(current.status())) {
			throw invalidState();
		}
		boolean approved = "APPROVE".equals(command.decisionCode());
		if (approved) {
			ensureSyntheticReady(current);
		}
		String targetStatus = approved ? "APPROVED" : "REJECTED";
		Instant now = Instant.now();
		jdbcClient.sql("""
				UPDATE content_version SET status = :status, reviewer_actor_id = :reviewerId,
				 review_decision_code = :decisionCode, reviewed_at = :now, updated_at = :now
				WHERE version_id = :versionId
				""")
				.param("status", targetStatus)
				.param("reviewerId", actor.accountId())
				.param("decisionCode", command.decisionCode())
				.param("now", Timestamp.from(now))
				.param("versionId", versionId)
				.update();
		idempotencyService.record(actor.accountId(), endpoint, key, payloadHash, "CONTENT_VERSION", versionId, 200, now);
		auditService.success(approved ? "CONTENT_REVIEW_APPROVED" : "CONTENT_REVIEW_REJECTED",
				actor.accountId(), actor.role().name(), "REVIEW_CONTENT", "CONTENT_VERSION", versionId,
				"IN_REVIEW", targetStatus, command.decisionCode(), null, requestId);
		return requireVersion(versionId);
	}

	@Transactional
	public ContentVersionView publish(SessionActor actor, String contentId, String versionId,
			String idempotencyKey, String requestId) {
		String endpoint = operationEndpoint("publish", contentId, versionId);
		String key = idempotencyService.requireValidKey(idempotencyKey);
		String payloadHash = Hashing.sha256(contentId + "|" + versionId + "|publish");
		Optional<IdempotencyService.Replay> replay = idempotencyService.find(actor.accountId(), endpoint, key, payloadHash);
		if (replay.isPresent()) {
			return requireVersion(replay.get().resourceId());
		}
		ContentVersionView current = requireVersionForContentForUpdate(contentId, versionId);
		if (actor.accountId().equals(current.authorActorId())
				|| actor.accountId().equals(current.submittedByActorId())) {
			throw selfReview();
		}
		if (!"APPROVED".equals(current.status())) {
			throw invalidState();
		}
		if (actor.role() != io.github.char0708.mch.platform.auth.AccountRole.PLATFORM_ADMIN
				&& !actor.accountId().equals(current.reviewerActorId())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "只有执行批准的不同复核者可以发布。");
		}
		ensureSyntheticReady(current);
		Instant now = Instant.now();
		jdbcClient.sql("UPDATE content_version SET status = 'PUBLISHED', published_at = :now, updated_at = :now WHERE version_id = :versionId")
				.param("now", Timestamp.from(now))
				.param("versionId", versionId)
				.update();
		jdbcClient.sql("UPDATE content_item SET current_published_version_id = :versionId WHERE content_id = :contentId")
				.param("versionId", versionId)
				.param("contentId", contentId)
				.update();
		idempotencyService.record(actor.accountId(), endpoint, key, payloadHash, "CONTENT_VERSION", versionId, 200, now);
		auditService.success("CONTENT_PUBLISHED", actor.accountId(), actor.role().name(), "PUBLISH_CONTENT",
				"CONTENT_VERSION", versionId, "APPROVED", "PUBLISHED", null, null, requestId);
		return requireVersion(versionId);
	}

	public OpsContentView getOpsContent(String contentId) {
		String currentPublished = jdbcClient.sql("SELECT current_published_version_id FROM content_item WHERE content_id = :contentId")
				.param("contentId", contentId)
				.query(String.class)
				.optional()
				.orElse(null);
		List<ContentVersionView> versions = jdbcClient.sql(versionSelect() + " WHERE content_id = :contentId ORDER BY version_number")
				.param("contentId", contentId)
				.query((resultSet, rowNumber) -> mapVersion(resultSet))
				.list();
		if (versions.isEmpty()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "内容资源不可访问。");
		}
		return new OpsContentView(contentId, currentPublished, versions);
	}

	public ContentSlotListView contentSlots(Integer dayOffset, String contentTypeCode, String status,
			boolean missingOnly) {
		List<ContentSlotView> items = jdbcClient.sql("""
				SELECT s.slot_code, s.pregnancy_week, s.day_offset, s.content_type_code, s.content_id,
				       v.version_id, v.version_number, v.title, v.status, v.revision, v.author_actor_id,
				       v.reviewer_actor_id, v.updated_at
				FROM r1_content_slot s
				LEFT JOIN content_version v ON v.content_id = s.content_id
				 AND v.version_number = (SELECT MAX(v2.version_number) FROM content_version v2 WHERE v2.content_id = s.content_id)
				ORDER BY s.day_offset, CASE s.content_type_code WHEN 'BABY_CHANGE' THEN 0 ELSE 1 END
				""")
				.query((resultSet, rowNumber) -> new ContentSlotView(
						resultSet.getString("slot_code"), resultSet.getInt("pregnancy_week"),
						resultSet.getInt("day_offset"), resultSet.getString("content_type_code"),
						"BABY_CHANGE".equals(resultSet.getString("content_type_code")) ? "宝宝变化" : "妈妈变化",
						resultSet.getString("content_id"), resultSet.getString("version_id"),
						resultSet.getObject("version_number") == null ? null : resultSet.getInt("version_number"),
						resultSet.getString("title"), resultSet.getString("status"),
						resultSet.getObject("revision") == null ? null : resultSet.getInt("revision"),
						resultSet.getString("author_actor_id"), resultSet.getString("reviewer_actor_id"),
						instant(resultSet, "updated_at")))
				.list().stream()
				.filter(item -> dayOffset == null || item.dayOffset() == dayOffset)
				.filter(item -> contentTypeCode == null || contentTypeCode.isBlank()
						|| contentTypeCode.equals(item.contentTypeCode()))
				.filter(item -> status == null || status.isBlank() || status.equals(item.status()))
				.filter(item -> !missingOnly || item.contentId() == null)
				.toList();
		return new ContentSlotListView(items, summarize(items));
	}

	public WorkbenchView workbench() {
		ContentSlotSummary summary = summarize(contentSlots(null, null, null, false).items());
		return new WorkbenchView(summary.totalSlots(), summary.publishedCount(), summary.draftCount(),
				summary.inReviewCount(), summary.approvedCount(), summary.missingCount(),
				summary.totalSlots() == 0 ? 0 : (summary.totalSlots() - summary.missingCount()) * 100 / summary.totalSlots());
	}

	public ReviewQueueView reviewQueue() {
		List<ReviewQueueItem> items = contentSlots(null, null, "IN_REVIEW", false).items().stream()
				.map(item -> new ReviewQueueItem(item.slotCode(), item.pregnancyWeek(), item.dayOffset(),
						item.contentTypeCode(), item.contentTypeLabel(), item.contentId(), item.versionId(),
						item.versionNumber(), item.title(), item.authorActorId(), item.updatedAt()))
				.toList();
		return new ReviewQueueView(items);
	}

	public List<PublishedContentView> findPublishedForStage(String stageCode) {
		return jdbcClient.sql(publishedSelect() + " WHERE v.status = 'PUBLISHED' AND v.target_stage_code = :stageCode ORDER BY v.published_at, v.version_id")
				.param("stageCode", stageCode)
				.query((resultSet, rowNumber) -> mapPublished(resultSet))
				.list();
	}

	public PublishedContentView getPublished(String contentId, String stageCode) {
		return jdbcClient.sql(publishedSelect() + " WHERE i.content_id = :contentId AND v.status = 'PUBLISHED' AND v.target_stage_code = :stageCode")
				.param("contentId", contentId)
				.param("stageCode", stageCode)
				.query((resultSet, rowNumber) -> mapPublished(resultSet))
				.optional()
				.orElseThrow(ContentService::notPublished);
	}

	public boolean isCurrentPublished(String contentId, String versionId) {
		Integer count = jdbcClient.sql("""
				SELECT COUNT(*) FROM content_item i JOIN content_version v ON v.version_id = i.current_published_version_id
				WHERE i.content_id = :contentId AND v.version_id = :versionId AND v.status = 'PUBLISHED'
				""")
				.param("contentId", contentId)
				.param("versionId", versionId)
				.query(Integer.class)
				.single();
		return count == 1;
	}

	public String statusOf(String versionId) {
		return requireVersion(versionId).status();
	}

	private ContentVersionView requireVersionForContentForUpdate(String contentId, String versionId) {
		ContentVersionView version = jdbcClient.sql(versionSelect() + " WHERE version_id = :versionId FOR UPDATE")
				.param("versionId", versionId)
				.query((resultSet, rowNumber) -> mapVersion(resultSet))
				.optional()
				.orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "内容资源不可访问。"));
		if (!contentId.equals(version.contentId())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "内容资源不可访问。");
		}
		return version;
	}

	private ContentVersionView requireVersion(String versionId) {
		return jdbcClient.sql(versionSelect() + " WHERE version_id = :versionId")
				.param("versionId", versionId)
				.query((resultSet, rowNumber) -> mapVersion(resultSet))
				.optional()
				.orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "内容资源不可访问。"));
	}

	private static String versionSelect() {
		return """
				SELECT version_id, content_id, version_number, title, body, summary_text, target_stage_code, content_basis,
				 source_reference, source_review_date, synthetic_display_label, professional_review_status,
				 status, author_actor_id, submitted_by_actor_id, reviewer_actor_id,
				 review_decision_code, submitted_at, reviewed_at, published_at, revision, created_at, updated_at
				 , (SELECT s.slot_code FROM r1_content_slot s WHERE s.content_id = content_version.content_id) AS slot_code
				FROM content_version
				""";
	}

	private static String publishedSelect() {
		return """
				SELECT i.content_id, v.version_id, v.version_number, v.title, v.body, v.target_stage_code,
				 v.synthetic_display_label, v.published_at
				FROM content_item i JOIN content_version v ON v.version_id = i.current_published_version_id
				""";
	}

	private static ContentVersionView mapVersion(java.sql.ResultSet resultSet) throws java.sql.SQLException {
		return new ContentVersionView(resultSet.getString("content_id"), resultSet.getString("version_id"),
				resultSet.getInt("version_number"), resultSet.getString("title"), resultSet.getString("body"),
				resultSet.getString("summary_text"), resultSet.getString("target_stage_code"),
				resultSet.getString("content_basis"), resultSet.getString("source_reference"),
				date(resultSet, "source_review_date"), resultSet.getString("slot_code"),
				resultSet.getString("synthetic_display_label"), resultSet.getString("professional_review_status"),
				resultSet.getString("status"), resultSet.getString("author_actor_id"),
				resultSet.getString("submitted_by_actor_id"), resultSet.getString("reviewer_actor_id"),
				resultSet.getString("review_decision_code"), instant(resultSet, "submitted_at"),
				instant(resultSet, "reviewed_at"), instant(resultSet, "published_at"), resultSet.getInt("revision"),
				instant(resultSet, "created_at"), instant(resultSet, "updated_at"));
	}

	private static PublishedContentView mapPublished(java.sql.ResultSet resultSet) throws java.sql.SQLException {
		return new PublishedContentView(resultSet.getString("content_id"), resultSet.getString("version_id"),
				resultSet.getInt("version_number"), resultSet.getString("title"), resultSet.getString("body"),
				resultSet.getString("target_stage_code"), resultSet.getString("synthetic_display_label"),
				instant(resultSet, "published_at"));
	}

	private static String instant(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
		Timestamp timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant().toString();
	}

	private static String date(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
		java.sql.Date value = resultSet.getDate(column);
		return value == null ? null : value.toLocalDate().toString();
	}

	private static void requireAuthor(SessionActor actor, ContentVersionView current) {
		if (!actor.accountId().equals(current.authorActorId())
				&& actor.role() != io.github.char0708.mch.platform.auth.AccountRole.PLATFORM_ADMIN) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "只有草稿作者可以编辑或提交。");
		}
	}

	private void validateSlot(String slotCode) {
		if (slotCode == null) {
			return;
		}
		if (!slotCode.matches("P20D[0-6]_(BABY|MOM)_CHANGE")) {
			throw invalid("slotCode", "R1_SLOT_REQUIRED");
		}
		Integer available = jdbcClient.sql("SELECT COUNT(*) FROM r1_content_slot WHERE slot_code = :slotCode AND content_id IS NULL")
				.param("slotCode", slotCode).query(Integer.class).single();
		if (available != 1) {
			throw new ApiException(HttpStatus.CONFLICT, "CONTENT_SLOT_OCCUPIED", "该内容槽位已存在内容。");
		}
	}

	private static ContentSlotSummary summarize(List<ContentSlotView> items) {
		int missing = (int) items.stream().filter(item -> item.contentId() == null).count();
		int published = (int) items.stream().filter(item -> "PUBLISHED".equals(item.status())).count();
		int draft = (int) items.stream().filter(item -> "DRAFT".equals(item.status()) || "REJECTED".equals(item.status())).count();
		int inReview = (int) items.stream().filter(item -> "IN_REVIEW".equals(item.status())).count();
		int approved = (int) items.stream().filter(item -> "APPROVED".equals(item.status())).count();
		return new ContentSlotSummary(items.size(), published, draft, inReview, approved, missing);
	}

	private static String nullable(String value) {
		return value == null ? "" : value;
	}

	private static java.sql.Date parseDate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return java.sql.Date.valueOf(value);
		}
		catch (IllegalArgumentException exception) {
			throw invalid("sourceReviewDate", "ISO_DATE_REQUIRED");
		}
	}

	private static void validateEditableContent(String title, String body, String stage, String basis) {
		if (title == null || title.isBlank() || title.length() > 80) {
			throw invalid("title", "LENGTH_1_TO_80");
		}
		if (body == null || body.isBlank() || body.length() > 2000 || DISALLOWED_MEDICAL_CLAIM.matcher(body).find()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SYNTHETIC_ONLY_VIOLATION",
					"内容必须是无医学主张的纯合成演示文字。");
		}
		if (!ProfileService.ADMITTED_STAGE.equals(stage)) {
			throw invalid("targetStageCode", "ONLY_EXPLICIT_SECOND_TRIMESTER_ADMITTED");
		}
		if (!"SYNTHETIC_DEMO".equals(basis)) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SYNTHETIC_ONLY_VIOLATION",
					"内容基础必须是 SYNTHETIC_DEMO。");
		}
	}

	private static void ensureSyntheticReady(ContentVersionView current) {
		if (!"SYNTHETIC_DEMO".equals(current.contentBasis()) || !SYNTHETIC_LABEL.equals(current.syntheticDisplayLabel())
				|| !ProfileService.ADMITTED_STAGE.equals(current.targetStageCode())
				|| !"NOT_APPLICABLE_SYNTHETIC".equals(current.professionalReviewStatus())) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SYNTHETIC_ONLY_VIOLATION",
					"合成内容标签或边界不完整，不能继续状态流转。");
		}
	}

	private static int parseRevision(String raw) {
		if (raw == null) {
			throw invalid("If-Match", "REQUIRED");
		}
		String normalized = raw.trim().replace("\"", "");
		try {
			int revision = Integer.parseInt(normalized);
			if (revision < 1) {
				throw new NumberFormatException();
			}
			return revision;
		}
		catch (NumberFormatException exception) {
			throw invalid("If-Match", "POSITIVE_REVISION_REQUIRED");
		}
	}

	private static String operationEndpoint(String operation, String contentId, String versionId) {
		return "POST:/api/v1/ops/contents/" + contentId + "/versions/" + versionId + "/" + operation;
	}

	private static ApiException invalidState() {
		return new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "当前内容状态不允许此操作。");
	}

	private static ApiException sourceNotCurrent() {
		return new ApiException(HttpStatus.CONFLICT, "SOURCE_VERSION_NOT_CURRENT", "源版本不是当前已发布版本。");
	}

	private static ApiException revisionAlreadyInProgress() {
		return new ApiException(HttpStatus.CONFLICT, "CONTENT_REVISION_ALREADY_IN_PROGRESS", "当前发布版本已有后继版本在流程中。");
	}

	private static ApiException selfReview() {
		return new ApiException(HttpStatus.CONFLICT, "SELF_REVIEW_FORBIDDEN", "内容作者不得复核或发布自己的版本。");
	}

	private static ApiException notPublished() {
		return new ApiException(HttpStatus.NOT_FOUND, "CONTENT_NOT_FOUND_OR_NOT_PUBLISHED", "内容不存在或尚未公开。");
	}

	private static ApiException invalid(String field, String reason) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "内容请求字段无效。",
				java.util.List.of(new ApiException.FieldError(field, reason)));
	}

	public record CreateContentCommand(String title, String body, String targetStageCode, String contentBasis,
			String slotCode, String summary, String sourceReference, String sourceReviewDate) {
	}

	public record UpdateContentCommand(String title, String body, String targetStageCode, String contentBasis,
			String summary, String sourceReference, String sourceReviewDate) {
	}

	public record CreateSuccessorDraftCommand(String sourceVersionId) {
	}

	public record ReviewCommand(String decisionCode) {
	}

	public record EmptyCommand() {
	}

	public record ContentVersionView(String contentId, String versionId, int versionNumber, String title, String body,
			String summary, String targetStageCode, String contentBasis, String sourceReference, String sourceReviewDate,
			String slotCode, String syntheticDisplayLabel, String professionalReviewStatus, String status,
			String authorActorId, String submittedByActorId, String reviewerActorId, String reviewDecisionCode,
			String submittedAt, String reviewedAt, String publishedAt, int revision, String createdAt, String updatedAt) {
	}

	public record OpsContentView(String contentId, String currentPublishedVersionId, List<ContentVersionView> versions) {
	}

	public record PublishedContentView(String contentId, String versionId, int versionNumber, String title, String body,
			String targetStageCode, String syntheticDisplayLabel, String publishedAt) {
	}

	public record ContentSlotView(String slotCode, int pregnancyWeek, int dayOffset, String contentTypeCode,
			String contentTypeLabel, String contentId, String versionId, Integer versionNumber, String title,
			String status, Integer revision, String authorActorId, String reviewerActorId, String updatedAt) {
	}

	public record ContentSlotSummary(int totalSlots, int publishedCount, int draftCount, int inReviewCount,
			int approvedCount, int missingCount) {
	}

	public record ContentSlotListView(List<ContentSlotView> items, ContentSlotSummary summary) {
	}

	public record WorkbenchView(int totalSlots, int publishedCount, int draftCount, int pendingReviewCount,
			int pendingPublishCount, int missingCount, int coveragePercent) {
	}

	public record ReviewQueueItem(String slotCode, int pregnancyWeek, int dayOffset, String contentTypeCode,
			String contentTypeLabel, String contentId, String versionId, Integer versionNumber, String title,
			String submitterActorId, String submittedAt) {
	}

	public record ReviewQueueView(List<ReviewQueueItem> items) {
	}
}
