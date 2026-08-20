package io.github.char0708.mch.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.char0708.mch.platform.auth.AccountRole;
import io.github.char0708.mch.platform.auth.SessionActor;
import io.github.char0708.mch.platform.shared.Identifiers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"mch.public-identity.synthetic-fixture.enabled=true",
		"mch.public-identity.synthetic-fixture.shared-assertion=R1_TEST_ASSERTION_32_BYTES_MINIMUM"
})
@AutoConfigureMockMvc
class ServiceContractIntegrationTests {

	private static final String DEFAULT_PERSON_ID = "00000000000000000000000000000001";
	private static final String IDENTITY_PROVIDER = "R1_SYNTHETIC_FIXTURE";
	private static final String DEFAULT_PERSON_REFERENCE = "r1-default-natural-person";
	private static final String IDENTITY_ASSERTION = "R1_TEST_ASSERTION_32_BYTES_MINIMUM";
	private static final String STAGE = "PREGNANCY_SECOND_TRIMESTER";
	private static final String DEFAULT_DISPLAY_NAME = "林女士";
	private static final LocalDate DEFAULT_DATE_OF_BIRTH = LocalDate.of(1994, 6, 15);
	private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hong_Kong");
	private static final String LABEL = "纯合成演示内容，不构成医疗建议";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	PlatformTransactionManager transactionManager;

	@BeforeEach
	void cleanDatabase() {
		jdbcClient.sql("UPDATE r1_content_slot SET content_id = NULL").update();
		for (String table : List.of("idempotency_record", "synthetic_audit_event",
				"content_version", "content_item", "weight_record", "weight_management_baseline", "explicit_stage_profile",
				"person_subject_relation", "account_subject_relation", "health_subject", "public_person_session",
				"natural_person", "synthetic_session", "synthetic_account")) {
			jdbcClient.sql("DELETE FROM " + table).update();
		}
		insertNaturalPerson(DEFAULT_PERSON_ID, DEFAULT_PERSON_REFERENCE);
	}

	@Test
	void shortDisplayIdentifiersDoNotReplaceInternalKeys() throws Exception {
		Member member = defaultPerson();
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);
		Operator admin = operator(AccountRole.PLATFORM_ADMIN);

		assertAll(
				() -> assertThat(member.personId()).isEqualTo(DEFAULT_PERSON_ID).hasSize(32),
				() -> assertThat(member.displayId()).isEqualTo("U000001"),
				() -> assertThat(editor.accountId()).hasSize(32),
				() -> assertThat(editor.displayId()).isEqualTo("OP" + editor.accountId().substring(28).toUpperCase()),
				() -> assertThat(reviewer.displayId()).isEqualTo("OP" + reviewer.accountId().substring(28).toUpperCase()),
				() -> assertThat(admin.displayId()).isEqualTo("OP" + admin.accountId().substring(28).toUpperCase()));
	}

	@Test
	void auto001NormalServiceVerticalSlicePublishingAndWeight() throws Exception {
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);
		PublishedContent content = publishContentAtSlot(editor, reviewer, "合成阶段生活记录", "纯合成日常记录示例",
				"P20D3_BABY_CHANGE");
		Member member = defaultPerson();
		String subjectId = createProfile(member, "SYNTHETIC_ALPHA");

		JsonNode emptyHome = getJson("/api/v1/me/home", member.token(), 200);
		assertAll(
				() -> assertThat(emptyHome.at("/data/stageCode").asText()).isEqualTo(STAGE),
				() -> assertThat(emptyHome.at("/data/contents/0/versionId").asText()).isEqualTo(content.versionId()),
				() -> assertThat(emptyHome.at("/data/latestWeight").isNull()).isTrue(),
				() -> assertThat(emptyHome.at("/data/trend").isEmpty()).isTrue());

		JsonNode detail = getJson("/api/v1/public/contents/" + content.contentId(), member.token(), 200);
		assertThat(detail.at("/data/syntheticDisplayLabel").asText()).isEqualTo(LABEL);

		Instant weightTime = Instant.now().minus(1, ChronoUnit.DAYS);
		JsonNode weight = postJson("/api/v1/me/health-subjects/" + subjectId + "/weights", Map.of(
				"value", "60.0", "unit", "KG", "businessTime", weightTime.toString(),
				"sourceType", "USER_SELF_REPORTED", "synthetic", true), key(), member.token(), 201);
		assertThat(weight.at("/data/value").asText()).isEqualTo("60.0");

		JsonNode home = getJson("/api/v1/me/home", member.token(), 200);
		assertAll(
				() -> assertThat(home.at("/data/latestWeight/value").asText()).isEqualTo("60.0"),
				() -> assertThat(contentStatus(content.versionId())).isEqualTo("PUBLISHED"));
	}

	@Test
	void r1HomeUsesClinicianDueDateForAuthoritativeD6DatingAndContents() throws Exception {
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);
		PublishedContent currentBaby = publishContentAtSlot(editor, reviewer,
				"20周第7天宝宝变化", "纯合成宝宝变化内容，用于当前孕周日首页展示。", "P20D6_BABY_CHANGE");
		PublishedContent currentMom = publishContentAtSlot(editor, reviewer,
				"20周第7天妈妈变化", "纯合成妈妈变化内容，用于当前孕周日首页展示。", "P20D6_MOM_CHANGE");
		PublishedContent staleBaby = publishContentAtSlot(editor, reviewer,
				"20周第4天宝宝变化", "纯合成其他日期宝宝内容，不应出现在当前首页。", "P20D3_BABY_CHANGE");
		PublishedContent staleMom = publishContentAtSlot(editor, reviewer,
				"20周第4天妈妈变化", "纯合成其他日期妈妈内容，不应出现在当前首页。", "P20D3_MOM_CHANGE");

		Member member = defaultPerson();
		Map<String, Object> profile = profilePayload("SYNTHETIC_HOME_DAILY", 143);
		profile.put("clinicianEstimatedDueDate", LocalDate.now(BUSINESS_ZONE).plusDays(134).toString());
		profileAttempt(member, profile, 201);
		JsonNode home = getJson("/api/v1/me/home", member.token(), 200);

		assertAll(
				() -> assertThat(home.at("/data/gestationalAgeWeeks").asInt()).isEqualTo(20),
				() -> assertThat(home.at("/data/gestationalAgeDays").asInt()).isEqualTo(6),
				() -> assertThat(home.at("/data/gestationalAgeDisplay").asText()).isEqualTo("20周+6天"),
				() -> assertThat(home.at("/data/daysUntilEstimatedDueDate").asInt()).isEqualTo(134),
				() -> assertThat(home.at("/data/contents")).hasSize(2),
				() -> assertThat(home.at("/data/contents/0/contentId").asText()).isEqualTo(currentBaby.contentId()),
				() -> assertThat(home.at("/data/contents/0/slotCode").asText()).isEqualTo("P20D6_BABY_CHANGE"),
				() -> assertThat(home.at("/data/contents/0/contentTypeCode").asText()).isEqualTo("BABY_CHANGE"),
				() -> assertThat(home.at("/data/contents/1/contentId").asText()).isEqualTo(currentMom.contentId()),
				() -> assertThat(home.at("/data/contents/1/slotCode").asText()).isEqualTo("P20D6_MOM_CHANGE"),
				() -> assertThat(home.at("/data/contents/1/contentTypeCode").asText()).isEqualTo("MOM_CHANGE"),
				() -> assertThat(home.at("/data/contents").toString()).doesNotContain(staleBaby.contentId())
						.doesNotContain(staleMom.contentId()),
				() -> assertThat(home.at("/data/contents").toString()).doesNotContain("authorActorId")
						.doesNotContain("reviewerActorId").doesNotContain("status"));
	}

	@Test
	void r1HomeOmitsAnUnpublishedCurrentSlotAndKeepsThePublishedSide() throws Exception {
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);
		PublishedContent currentBaby = publishContentAtSlot(editor, reviewer,
				"20周第5天宝宝变化", "宝宝今天继续成长，保持日常观察。", "P20D4_BABY_CHANGE");
		PublishedContent unpublishedMom = createDraftAtSlot(editor,
				"20周第5天妈妈变化", "妈妈今天的内容仍在复核前，不应公开。", "P20D4_MOM_CHANGE");
		publishContentAtSlot(editor, reviewer,
				"20周第4天妈妈变化", "这是前一天已经发布的妈妈变化。", "P20D3_MOM_CHANGE");

		Member member = defaultPerson();
		profileAttempt(member, profilePayload("SYNTHETIC_HOME_ONE_SIDE", 144), 201);
		JsonNode home = getJson("/api/v1/me/home", member.token(), 200);

		assertAll(
				() -> assertThat(home.at("/data/contents")).hasSize(1),
				() -> assertThat(home.at("/data/contents/0/contentId").asText()).isEqualTo(currentBaby.contentId()),
				() -> assertThat(home.at("/data/contents/0/contentTypeCode").asText()).isEqualTo("BABY_CHANGE"),
				() -> assertThat(home.at("/data/contents").toString()).doesNotContain(unpublishedMom.contentId())
						.doesNotContain("DRAFT"));
	}

	@Test
	void auto002And003DefaultNaturalPersonAndTrustedIdentityFailClosed() throws Exception {
		assertAll(
				() -> assertThat(count("natural_person")).isEqualTo(1),
				() -> assertThat(count("public_person_session")).isZero(),
				() -> assertThat(count("health_subject")).isZero(),
				() -> assertThat(count("person_subject_relation")).isZero(),
				() -> assertThat(count("explicit_stage_profile")).isZero(),
				() -> assertThat(count("weight_record")).isZero(),
				() -> assertThat(jdbcClient.sql("SELECT profile_status FROM natural_person WHERE person_id = :personId")
						.param("personId", DEFAULT_PERSON_ID).query(String.class).single()).isEqualTo("REQUIRED"));
		assertNaturalPersonSchemaHasOnlyApprovedDisplayAttributes();

		assertError(postIdentity(Map.of(), null, null, null, "127.0.0.1", 401), "AUTHENTICATION_FAILED");
		assertError(postIdentity(Map.of(), IDENTITY_PROVIDER, DEFAULT_PERSON_REFERENCE, "wrong-assertion",
				"127.0.0.1", 401), "AUTHENTICATION_FAILED");
		assertError(postIdentity(Map.of(), IDENTITY_PROVIDER, "unknown-person", IDENTITY_ASSERTION,
				"127.0.0.1", 401), "AUTHENTICATION_FAILED");
		assertError(postIdentity(Map.of(), IDENTITY_PROVIDER, DEFAULT_PERSON_REFERENCE, IDENTITY_ASSERTION,
				"198.51.100.10", 401), "AUTHENTICATION_FAILED");

		JsonNode session = postIdentity(Map.of(), IDENTITY_PROVIDER, DEFAULT_PERSON_REFERENCE, IDENTITY_ASSERTION,
				"127.0.0.1", 201);
		assertAll(
				() -> assertThat(session.at("/data/personId").asText()).isEqualTo(DEFAULT_PERSON_ID),
				() -> assertThat(session.at("/data/principalType").asText()).isEqualTo("NATURAL_PERSON"),
				() -> assertThat(session.at("/data/profileStatus").asText()).isEqualTo("REQUIRED"),
				() -> assertThat(session.at("/data/subjectId").isNull()).isTrue(),
				() -> assertThat(session.at("/data/displayName").asText()).isEqualTo(DEFAULT_DISPLAY_NAME),
				() -> assertThat(session.at("/data/ageYears").asInt()).isEqualTo(
						Period.between(DEFAULT_DATE_OF_BIRTH, LocalDate.now(BUSINESS_ZONE)).getYears()),
				() -> assertThat(session.toString()).doesNotContain("dateOfBirth")
						.doesNotContain(DEFAULT_DATE_OF_BIRTH.toString())
						.doesNotContain("phone").doesNotContain("nationalId"),
				() -> assertThat(count("public_person_session")).isEqualTo(1),
				() -> assertThat(count("synthetic_account")).isZero());

		mockMvc.perform(post("/api/v1/synthetic-auth/registrations").contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void auto004To006And008ProfileAtomicityRequiredStateRelationBoundaryAndExplicitStage() throws Exception {
		Member member = defaultPerson();
		JsonNode beforeProfile = getJson("/api/v1/me/home", member.token(), 409);
		assertError(beforeProfile, "PROFILE_REQUIRED");

		Map<String, Object> missingAlias = profilePayload("SYNTHETIC_MISSING", 143);
		missingAlias.remove("syntheticAlias");
		assertError(postJson("/api/v1/me/health-subjects/self", missingAlias, key(), member.token(), 400), "INVALID_REQUEST");
		assertProfileCounts(0);

		Map<String, Object> child = profilePayload("SYNTHETIC_CHILD", 143);
		child.put("subjectType", "CHILD");
		assertError(profileAttempt(member, child, 409), "RELATION_FLOW_NOT_ADMITTED");
		Map<String, Object> childManager = profilePayload("SYNTHETIC_REL", 143);
		childManager.put("relationType", "CHILD_MANAGER");
		assertError(profileAttempt(member, childManager, 409), "RELATION_FLOW_NOT_ADMITTED");
		Map<String, Object> motherOf = profilePayload("SYNTHETIC_REL2", 143);
		motherOf.put("subjectRelationshipType", "MOTHER_OF");
		assertError(profileAttempt(member, motherOf, 409), "RELATION_FLOW_NOT_ADMITTED");
		assertProfileCounts(0);

		Map<String, Object> preconception = profilePayload("SYNTHETIC_PRE", 143);
		preconception.put("serviceMode", "PRECONCEPTION");
		assertError(profileAttempt(member, preconception, 400), "INVALID_REQUEST");
		Map<String, Object> wrongStage = profilePayload("SYNTHETIC_STAGE", 143);
		wrongStage.put("stageCode", "PREGNANCY_FIRST_TRIMESTER");
		assertError(profileAttempt(member, wrongStage, 400), "INVALID_REQUEST");
		Map<String, Object> futureStageTime = profilePayload("SYNTHETIC_FUTURE", 143);
		futureStageTime.put("stageBusinessTime", Instant.now().plus(1, ChronoUnit.DAYS).toString());
		assertError(profileAttempt(member, futureStageTime, 422), "BUSINESS_TIME_IN_FUTURE");
		Map<String, Object> invalidDuration = profilePayload("SYNTHETIC_DURATION", 143);
		invalidDuration.put("averageMenstrualDurationDays", 16);
		assertError(profileAttempt(member, invalidDuration, 400), "INVALID_REQUEST");
		Map<String, Object> futureLmp = profilePayload("SYNTHETIC_LMP", 143);
		futureLmp.put("lastMenstrualPeriodDate", LocalDate.now(BUSINESS_ZONE).plusDays(1).toString());
		assertError(profileAttempt(member, futureLmp, 400), "INVALID_REQUEST");
		assertProfileCounts(0);

		assertError(profileAttempt(member, profilePayload("SYNTHETIC_19W6D", 139), 422),
				"GESTATIONAL_AGE_OUT_OF_R1_RANGE");
		assertError(profileAttempt(member, profilePayload("SYNTHETIC_21W0D", 147), 422),
				"GESTATIONAL_AGE_OUT_OF_R1_RANGE");
		assertProfileCounts(0);

		Map<String, Object> firstDay = profilePayload("SYNTHETIC_20W0D", 140);
		String firstDayKey = key();
		JsonNode profile = postJson("/api/v1/me/health-subjects/self", firstDay, firstDayKey, member.token(), 201);
		JsonNode replay = postJson("/api/v1/me/health-subjects/self", firstDay, firstDayKey, member.token(), 201);
		assertAll(
				() -> assertThat(replay.at("/data/subjectId").asText()).isEqualTo(profile.at("/data/subjectId").asText()),
				() -> assertThat(profile.at("/data/relation/relationType").asText()).isEqualTo("SELF"),
				() -> assertThat(profile.at("/data/stageSourceType").asText()).isEqualTo("USER_SELF_REPORTED"),
				() -> assertThat(profile.at("/data/serviceMode").asText()).isEqualTo("PREGNANCY"),
				() -> assertThat(profile.at("/data/estimatedDueDateSource").asText()).isEqualTo("LMP_280_DAY_ESTIMATE"),
				() -> assertThat(profile.at("/data/estimatedDueDate").asText()).isEqualTo(
						LocalDate.parse((String) firstDay.get("lastMenstrualPeriodDate")).plusDays(280).toString()),
				() -> assertThat(profile.at("/data/gestationalAgeDisplay").asText()).isEqualTo("20周+0天"),
				() -> assertThat(profile.at("/data/gestationalAgeWeeks").asInt()).isEqualTo(20),
				() -> assertThat(profile.at("/data/gestationalAgeDays").asInt()).isZero());
		assertProfileCounts(1);

		Member lastDayMember = additionalPerson("r1-last-day-person-" + suffix());
		JsonNode lastDay = profileAttempt(lastDayMember, profilePayload("SYNTHETIC_20W6D", 146), 201);
		assertThat(lastDay.at("/data/gestationalAgeDisplay").asText()).isEqualTo("20周+6天");
		assertProfileCounts(2);

		Member clinicianMember = additionalPerson("r1-clinician-person-" + suffix());
		LocalDate referenceDate = LocalDate.now(BUSINESS_ZONE);
		Map<String, Object> clinicianPayload = profilePayload("SYNTHETIC_CLINICIAN", 10);
		LocalDate clinicianDueDate = referenceDate.plusDays(137);
		clinicianPayload.put("clinicianEstimatedDueDate", clinicianDueDate.toString());
		JsonNode clinicianProfile = profileAttempt(clinicianMember, clinicianPayload, 201);
		assertAll(
				() -> assertThat(clinicianProfile.at("/data/estimatedDueDate").asText()).isEqualTo(clinicianDueDate.toString()),
				() -> assertThat(clinicianProfile.at("/data/estimatedDueDateSource").asText())
						.isEqualTo("USER_REPORTED_CLINICIAN_ESTIMATE"),
				() -> assertThat(clinicianProfile.at("/data/gestationalAgeDisplay").asText()).isEqualTo("20周+3天"),
				() -> assertThat(jdbcClient.sql("SELECT average_menstrual_duration_days FROM explicit_stage_profile WHERE subject_id = :subjectId")
						.param("subjectId", clinicianProfile.at("/data/subjectId").asText()).query(Integer.class).single()).isEqualTo(5));
		assertProfileCounts(3);
	}

	@Test
	void auto007And023SubjectAuthorizationAndOpsHealthIsolation() throws Exception {
		Member first = defaultPerson();
		Member second = additionalPerson("r1-second-person-" + suffix());
		String firstSubject = createProfile(first, "SYNTHETIC_FIRST");
		String secondSubject = createProfile(second, "SYNTHETIC_SECOND");
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);

		JsonNode deniedRead = getJson("/api/v1/me/health-subjects/" + secondSubject + "/weights", first.token(), 403);
		assertError(deniedRead, "FORBIDDEN");
		assertThat(deniedRead.toString()).doesNotContain("records").doesNotContain(secondSubject);
		for (String token : List.of(editor.token(), reviewer.token())) {
			JsonNode wrongRealm = getJson("/api/v1/me/health-subjects/" + secondSubject + "/weights", token, 401);
			assertError(wrongRealm, "AUTHENTICATION_REQUIRED");
			assertThat(wrongRealm.toString()).doesNotContain("records").doesNotContain(secondSubject);
		}
		JsonNode deniedWrite = postJson("/api/v1/me/health-subjects/" + secondSubject + "/weights", Map.of(
				"value", "60", "unit", "KG", "businessTime", Instant.now().minusSeconds(5).toString(),
				"sourceType", "USER_SELF_REPORTED", "synthetic", true), key(), first.token(), 403);
		assertError(deniedWrite, "FORBIDDEN");
		assertThat(count("weight_record")).isZero();

		JsonNode opsProfile = postJson("/api/v1/me/health-subjects/self",
				profilePayload("SYNTHETIC_OPS", 143), key(), editor.token(), 401);
		assertError(opsProfile, "AUTHENTICATION_REQUIRED");
		JsonNode personOnOps = postJson("/api/v1/ops/contents", Map.of("title", "纯合成隔离", "body", "纯合成隔离正文",
				"targetStageCode", STAGE, "contentBasis", "SYNTHETIC_DEMO"), key(), first.token(), 401);
		assertError(personOnOps, "AUTHENTICATION_REQUIRED");
		assertThat(firstSubject).isNotEqualTo(secondSubject);
	}

	@Test
	void auto009To014ContentStateMachineRoleSeparationVisibilityLabelGateAndExactStage() throws Exception {
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);
		Member member = defaultPerson();
		createProfile(member, "SYNTHETIC_CONTENT");

		PublishedContent revisionDraft = createDraft(editor, "纯合成并发草稿", "纯合成并发正文");
		Map<String, Object> revisedContent = Map.of("title", "纯合成并发更新", "body", "纯合成并发更新正文",
				"targetStageCode", STAGE, "contentBasis", "SYNTHETIC_DEMO");
		assertError(putJson(versionPath(revisionDraft), revisedContent, "99", key(), editor.token(), 409),
				"CONTENT_VERSION_CONFLICT");
		JsonNode revised = putJson(versionPath(revisionDraft), revisedContent, "1", key(), editor.token(), 200);
		assertThat(revised.at("/data/revision").asInt()).isEqualTo(2);

		PublishedContent draft = createDraft(editor, "纯合成草稿", "纯合成生活记录");
		assertUnifiedNotPublished(draft.contentId(), member.token());
		submit(editor, draft);
		assertUnifiedNotPublished(draft.contentId(), member.token());
		JsonNode directPublish = postJson(operationPath(draft, "publish"), Map.of(), key(), editor.token(), 403);
		assertError(directPublish, "FORBIDDEN");

		jdbcClient.sql("UPDATE synthetic_account SET account_type = 'CONTENT_REVIEWER' WHERE account_id = :accountId")
				.param("accountId", editor.accountId()).update();
		JsonNode selfReview = postJson(operationPath(draft, "review-decisions"), Map.of("decisionCode", "APPROVE"),
				key(), editor.token(), 409);
		assertError(selfReview, "SELF_REVIEW_FORBIDDEN");
		assertThat(contentStatus(draft.versionId())).isEqualTo("IN_REVIEW");
		jdbcClient.sql("UPDATE synthetic_account SET account_type = 'CONTENT_EDITOR' WHERE account_id = :accountId")
				.param("accountId", editor.accountId()).update();

		review(reviewer, draft, "APPROVE");
		assertUnifiedNotPublished(draft.contentId(), member.token());
		JsonNode cannotEditApproved = putJson(versionPath(draft), Map.of("title", "纯合成更新", "body", "纯合成更新正文",
				"targetStageCode", STAGE, "contentBasis", "SYNTHETIC_DEMO"), "1", key(), editor.token(), 409);
		assertError(cannotEditApproved, "INVALID_STATE_TRANSITION");
		publish(reviewer, draft);
		assertThat(getJson("/api/v1/public/contents/" + draft.contentId(), member.token(), 200)
				.at("/data/status").isMissingNode()).isTrue();
		JsonNode cannotEditPublished = putJson(versionPath(draft), Map.of("title", "纯合成更新", "body", "纯合成更新正文",
				"targetStageCode", STAGE, "contentBasis", "SYNTHETIC_DEMO"), "1", key(), editor.token(), 409);
		assertError(cannotEditPublished, "INVALID_STATE_TRANSITION");

		jdbcClient.sql("UPDATE content_version SET target_stage_code = 'PREGNANCY_FIRST_TRIMESTER' WHERE version_id = :versionId")
				.param("versionId", draft.versionId()).update();
		assertUnifiedNotPublished(draft.contentId(), member.token());
		assertThat(getJson("/api/v1/me/home", member.token(), 200).at("/data/contents").isEmpty()).isTrue();
		jdbcClient.sql("UPDATE content_version SET target_stage_code = :stage WHERE version_id = :versionId")
				.param("stage", STAGE).param("versionId", draft.versionId()).update();

		PublishedContent badLabel = createDraft(editor, "纯合成标签门禁", "纯合成标签检查");
		jdbcClient.sql("UPDATE content_version SET synthetic_display_label = '' WHERE version_id = :versionId")
				.param("versionId", badLabel.versionId()).update();
		assertError(postJson(operationPath(badLabel, "submit"), Map.of(), key(), editor.token(), 422),
				"SYNTHETIC_ONLY_VIOLATION");
		assertThat(contentStatus(badLabel.versionId())).isEqualTo("DRAFT");

		PublishedContent rejected = createDraft(editor, "纯合成拒绝流程", "纯合成范围检查");
		submit(editor, rejected);
		review(reviewer, rejected, "REJECT_OUT_OF_SCOPE");
		assertThat(contentStatus(rejected.versionId())).isEqualTo("REJECTED");
		assertUnifiedNotPublished(rejected.contentId(), member.token());
	}

	@Test
	void auto015To019WeightPersistenceIdempotencyStableTrendEmptyAndFutureTime() throws Exception {
		Member member = defaultPerson();
		String subjectId = createProfile(member, "SYNTHETIC_WEIGHT");
		JsonNode empty = getJson("/api/v1/me/health-subjects/" + subjectId + "/weights", member.token(), 200);
		assertAll(
				() -> assertThat(empty.at("/data/records").isEmpty()).isTrue(),
				() -> assertThat(empty.at("/data/trend").isEmpty()).isTrue(),
				() -> assertThat(empty.at("/data/latestWeight").isNull()).isTrue());

		Instant sharedTime = Instant.now().minus(1, ChronoUnit.DAYS);
		String idempotencyKey = key();
		Map<String, Object> firstPayload = weightPayload("60.0", sharedTime);
		JsonNode first = postJson(weightPath(subjectId), firstPayload, idempotencyKey, member.token(), 201);
		JsonNode replay = postJson(weightPath(subjectId), firstPayload, idempotencyKey, member.token(), 201);
		assertThat(replay.at("/data/recordId").asText()).isEqualTo(first.at("/data/recordId").asText());
		assertThat(count("weight_record")).isEqualTo(1);

		JsonNode semanticDuplicate = postJson(weightPath(subjectId), weightPayload("60.00", sharedTime),
				key(), member.token(), 409);
		assertError(semanticDuplicate, "DUPLICATE_WEIGHT_RECORD");
		postJson(weightPath(subjectId), weightPayload("61", sharedTime), key(), member.token(), 201);
		postJson(weightPath(subjectId), weightPayload("59.25", sharedTime.minus(1, ChronoUnit.DAYS)), key(), member.token(), 201);

		JsonNode timeline = getJson(weightPath(subjectId), member.token(), 200);
		assertAll(
				() -> assertThat(timeline.at("/data/records/0/value").asText()).isEqualTo("59.25"),
				() -> assertThat(timeline.at("/data/records/1/value").asText()).isEqualTo("60.0"),
				() -> assertThat(timeline.at("/data/records/2/value").asText()).isEqualTo("61"),
				() -> assertThat(timeline.at("/data/latestWeight/value").asText()).isEqualTo("61"),
				() -> assertThat(timeline.toString()).doesNotContain("risk").doesNotContain("diagnosis").doesNotContain("advice"));

		JsonNode future = postJson(weightPath(subjectId), weightPayload("62", Instant.now().plus(1, ChronoUnit.DAYS)),
				key(), member.token(), 422);
		assertError(future, "BUSINESS_TIME_IN_FUTURE");
		assertThat(count("weight_record")).isEqualTo(3);
	}

	@Test
	void dec053BaselineCanBeSavedBeforeTheFirstWeightAndRemainsSubjectScoped() throws Exception {
		Member member = defaultPerson();
		Member other = additionalPerson("r1-baseline-other-" + suffix());
		String subjectId = createProfile(member, "SYNTHETIC_BASELINE_ONLY");
		String otherSubjectId = createProfile(other, "SYNTHETIC_BASELINE_OTHER");
		JsonNode saved = putJson(weightPath(subjectId) + "/baseline", Map.of(
				"prePregnancyWeightKg", "54.0", "heightCm", "165.0"), null, key(), member.token(), 200);
		assertAll(
				() -> assertThat(saved.at("/data/prePregnancyWeightKg").asText()).isEqualTo("54.0"),
				() -> assertThat(saved.at("/data/heightCm").asText()).isEqualTo("165.0"),
				() -> assertThat(saved.at("/data/bmi").asText()).isEqualTo("19.8"));

		JsonNode timeline = getJson(weightPath(subjectId), member.token(), 200);
		assertAll(
				() -> assertThat(timeline.at("/data/records").isEmpty()).isTrue(),
				() -> assertThat(timeline.at("/data/baseline/bmi").asText()).isEqualTo("19.8"));

		JsonNode denied = putJson(weightPath(otherSubjectId) + "/baseline", Map.of(
				"prePregnancyWeightKg", "60.0", "heightCm", "170.0"), null, key(), member.token(), 403);
		assertError(denied, "FORBIDDEN");
		assertThat(getJson(weightPath(otherSubjectId), other.token(), 200).at("/data/baseline").isNull()).isTrue();

		JsonNode incomplete = putJson(weightPath(subjectId) + "/baseline", Map.of(
				"prePregnancyWeightKg", "54.0"), null, key(), member.token(), 400);
		assertError(incomplete, "INVALID_REQUEST");
	}

	@Test
	void auto028WeightManagementBaselineBmiMoodAndStageAdvice() throws Exception {
		Member member = defaultPerson();
		String subjectId = createProfile(member, "SYNTHETIC_WEIGHT_MANAGEMENT");
		Instant businessTime = Instant.now().minus(2, ChronoUnit.HOURS);
		Map<String, Object> payload = new HashMap<>(weightPayload("60.2", businessTime));
		payload.put("prePregnancyWeightKg", "54.0");
		payload.put("heightCm", "165.0");
		payload.put("moodCode", "HAPPY");

		JsonNode recorded = postJson(weightPath(subjectId), payload, key(), member.token(), 201);
		assertThat(recorded.at("/data/moodCode").asText()).isEqualTo("HAPPY");

		JsonNode timeline = getJson(weightPath(subjectId), member.token(), 200);
		assertAll(
				() -> assertThat(timeline.at("/data/baseline/prePregnancyWeightKg").asText()).isEqualTo("54.0"),
				() -> assertThat(timeline.at("/data/baseline/heightCm").asText()).isEqualTo("165.0"),
				() -> assertThat(timeline.at("/data/baseline/bmi").asText()).isEqualTo("19.8"),
				() -> assertThat(timeline.at("/data/records/0/moodCode").asText()).isEqualTo("HAPPY"),
				() -> assertThat(timeline.at("/data/managementAdvice/stageCode").asText()).isEqualTo(STAGE),
				() -> assertThat(timeline.at("/data/managementAdvice/body").asText())
						.isEqualTo("体重变化需要结合孕前情况和专业建议综合判断。保持规律记录，比只关注某一次数值更有参考意义。"),
				() -> assertThat(timeline.at("/data/managementAdvice/personalized").asBoolean()).isFalse(),
				() -> assertThat(count("weight_management_baseline")).isEqualTo(1));

		Map<String, Object> invalidMood = new HashMap<>(weightPayload("60.3", businessTime.plusSeconds(1)));
		invalidMood.put("prePregnancyWeightKg", "54.0");
		invalidMood.put("heightCm", "165.0");
		invalidMood.put("moodCode", "EXCITED");
		assertError(postJson(weightPath(subjectId), invalidMood, key(), member.token(), 400), "INVALID_REQUEST");

		Map<String, Object> incompleteBaseline = new HashMap<>(weightPayload("60.4", businessTime.plusSeconds(2)));
		incompleteBaseline.put("prePregnancyWeightKg", "54.0");
		incompleteBaseline.put("moodCode", "CALM");
		assertError(postJson(weightPath(subjectId), incompleteBaseline, key(), member.token(), 400), "INVALID_REQUEST");
		assertThat(count("weight_record")).isEqualTo(1);

		Map<String, Object> changedWeight = new HashMap<>(weightPayload("90.0", businessTime.plusSeconds(3)));
		changedWeight.put("prePregnancyWeightKg", "54.0");
		changedWeight.put("heightCm", "165.0");
		changedWeight.put("moodCode", "CALM");
		postJson(weightPath(subjectId), changedWeight, key(), member.token(), 201);
		JsonNode unchangedAdvice = getJson(weightPath(subjectId), member.token(), 200);
		assertAll(
				() -> assertThat(unchangedAdvice.at("/data/managementAdvice/body").asText())
						.isEqualTo("体重变化需要结合孕前情况和专业建议综合判断。保持规律记录，比只关注某一次数值更有参考意义。"),
				() -> assertThat(unchangedAdvice.at("/data/managementAdvice/personalized").asBoolean()).isFalse(),
				() -> assertThat(unchangedAdvice.at("/data/records/1/moodCode").asText()).isEqualTo("CALM"),
				() -> assertThat(count("weight_management_baseline")).isEqualTo(1),
				() -> assertThat(count("weight_record")).isEqualTo(2));
	}

	@Test
	void r1ContentMetricsEndpointsAndTablesAreAbsent() throws Exception {
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		MvcResult removedPublicEndpoint = mockMvc.perform(post("/api/v1/public/content-events")
				.contentType(MediaType.APPLICATION_JSON).content("{}")
				.requestAttr(SessionActor.REQUEST_ATTRIBUTE,
						new SessionActor(editor.accountId(), editor.role(), "REQUIRED")))
				.andExpect(status().isNotFound()).andReturn();
		mockMvc.perform(get("/api/v1/ops/contents/content/version/version/metrics"))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/ops/metrics/feedback?contentId=content&versionId=version"))
				.andExpect(status().isNotFound());

		Integer tableCount = jdbcClient.sql("""
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema = DATABASE()
				  AND table_name IN ('content_metric_event', 'content_metric_aggregate')
				""").query(Integer.class).single();
		Map<String, Object> auditRow = jdbcClient.sql("""
				SELECT actor_id FROM synthetic_audit_event
				WHERE trace_id = :traceId AND error_code = 'NOT_FOUND'
				ORDER BY occurred_at DESC LIMIT 1
				""").param("traceId", removedPublicEndpoint.getResponse().getHeader("X-Request-Id"))
				.query().singleRow();
		assertAll(
				() -> assertThat(tableCount).isZero(),
				() -> assertThat(auditRow.get("actor_id")).isEqualTo(editor.accountId()));
	}

	@Test
	void auto024And025AuditIsMinimalAndErrorEnvelopeDoesNotLeak() throws Exception {
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);
		PublishedContent draft = createDraft(editor, "不可泄露的纯合成标题", "不可泄露的纯合成正文");
		Member member = defaultPerson();
		String subjectId = createProfile(member, "SYNTHETIC_AUDIT");

		JsonNode hidden = getJson("/api/v1/public/contents/" + draft.contentId(), member.token(), 404);
		assertError(hidden, "CONTENT_NOT_FOUND_OR_NOT_PUBLISHED");
		assertThat(hidden.toString()).doesNotContain(draft.contentId()).doesNotContain("纯合成标题")
				.doesNotContain("DRAFT").doesNotContain("stack").doesNotContain("sql");

		Instant time = Instant.now().minus(1, ChronoUnit.DAYS);
		postJson(weightPath(subjectId), weightPayload("58.5", time), key(), member.token(), 201);
		assertError(postJson(weightPath(subjectId), weightPayload("58.50", time), key(), member.token(), 409),
				"DUPLICATE_WEIGHT_RECORD");
		publishContent(editor, reviewer, "纯合成审计内容", "纯合成审计正文");

		List<String> eventNames = jdbcClient.sql("SELECT event_name FROM synthetic_audit_event")
				.query(String.class).list();
		assertThat(eventNames).contains("PUBLIC_IDENTITY_SESSION_ISSUED", "SELF_PROFILE_CREATED", "WEIGHT_RECORDED",
				"DUPLICATE_REJECTED", "CONTENT_DRAFT_CREATED", "CONTENT_SUBMITTED", "CONTENT_REVIEW_APPROVED",
				"CONTENT_PUBLISHED", "REQUEST_REJECTED");

		String auditText = String.join("|", jdbcClient.sql("""
				SELECT CONCAT_WS('|', event_name, COALESCE(actor_id,''), COALESCE(actor_role,''), action_name,
				 COALESCE(resource_type,''), COALESCE(resource_id,''), result_status, COALESCE(error_code,''),
				 COALESCE(from_status,''), COALESCE(to_status,''), COALESCE(decision_code,''),
				 COALESCE(metric_event_type,''), trace_id)
				FROM synthetic_audit_event
				""").query(String.class).list());
		assertThat(auditText).doesNotContain(IDENTITY_ASSERTION).doesNotContain(DEFAULT_PERSON_REFERENCE).doesNotContain("58.5")
				.doesNotContain("纯合成审计正文").doesNotContain(member.token())
				.doesNotContain(DEFAULT_DISPLAY_NAME).doesNotContain(DEFAULT_DATE_OF_BIRTH.toString())
				.doesNotContain(LocalDate.now(BUSINESS_ZONE).minusDays(143).toString());
	}

	@Test
	void auto026SyntheticOnlyAndClosedRequestBoundariesFailClosed() throws Exception {
		JsonNode identityWithClientPrincipal = postIdentity(Map.of("personId", DEFAULT_PERSON_ID), IDENTITY_PROVIDER,
				DEFAULT_PERSON_REFERENCE, IDENTITY_ASSERTION, "127.0.0.1", 400);
		assertError(identityWithClientPrincipal, "INVALID_REQUEST");
		assertThat(count("public_person_session")).isZero();

		Member member = defaultPerson();
		Map<String, Object> nonSynthetic = profilePayload("SYNTHETIC_FALSE", 143);
		nonSynthetic.put("synthetic", false);
		assertError(profileAttempt(member, nonSynthetic, 422), "SYNTHETIC_ONLY_VIOLATION");
		assertThat(count("health_subject")).isZero();

		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		JsonNode content = postJson("/api/v1/ops/contents", Map.of("title", "纯合成边界", "body", "纯合成边界正文",
				"targetStageCode", STAGE, "contentBasis", "PROFESSIONALLY_CONFIRMED"), key(), editor.token(), 422);
		assertError(content, "SYNTHETIC_ONLY_VIOLATION");
		JsonNode medicalClaim = postJson("/api/v1/ops/contents", Map.of("title", "纯合成边界", "body", "这是诊断文字",
				"targetStageCode", STAGE, "contentBasis", "SYNTHETIC_DEMO"), key(), editor.token(), 422);
		assertError(medicalClaim, "SYNTHETIC_ONLY_VIOLATION");

		int accountsBeforeUnknownField = count("synthetic_account");
		Map<String, Object> unknownAuthField = new HashMap<>();
		unknownAuthField.put("username", "operator" + suffix());
		unknownAuthField.put("password", "SyntheticOps!" + suffix());
		unknownAuthField.put("accountType", "CONTENT_REVIEWER");
		assertError(postJson("/api/v1/synthetic-auth/sessions", unknownAuthField, null, null, 400), "INVALID_REQUEST");
		assertThat(count("synthetic_account")).isEqualTo(accountsBeforeUnknownField);
	}

	@Test
	void r1Ops001ProvidesFourteenSlotsWorkbenchQueueAndPublishing() throws Exception {
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);

		JsonNode slots = getJson("/api/v1/ops/content-slots", editor.token(), 200);
		assertAll(
				() -> assertThat(slots.at("/data/items")).hasSize(14),
				() -> assertThat(slots.at("/data/items/0/slotCode").asText()).isEqualTo("P20D0_BABY_CHANGE"),
				() -> assertThat(slots.at("/data/items/13/slotCode").asText()).isEqualTo("P20D6_MOM_CHANGE"));

		JsonNode draft = postJson("/api/v1/ops/contents", Map.of(
				"title", "20周第1天宝宝变化", "body", "纯合成宝宝变化内容，用于验证运营闭环。",
				"targetStageCode", STAGE, "contentBasis", "SYNTHETIC_DEMO",
				"slotCode", "P20D0_BABY_CHANGE"), key(), editor.token(), 201);
		PublishedContent content = new PublishedContent(draft.at("/data/contentId").asText(),
				draft.at("/data/versionId").asText());
		assertThat(draft.at("/data/slotCode").asText()).isEqualTo("P20D0_BABY_CHANGE");

		JsonNode workbench = getJson("/api/v1/ops/workbench", editor.token(), 200);
		assertAll(
				() -> assertThat(workbench.at("/data/totalSlots").asInt()).isEqualTo(14),
				() -> assertThat(workbench.at("/data/draftCount").asInt()).isEqualTo(1),
				() -> assertThat(workbench.at("/data/missingCount").asInt()).isEqualTo(13));

		submit(editor, content);
		JsonNode queue = getJson("/api/v1/ops/review-queue", reviewer.token(), 200);
		assertThat(queue.at("/data/items/0/contentId").asText()).isEqualTo(content.contentId());
		review(reviewer, content, "APPROVE");
		publish(reviewer, content);
		assertThat(contentStatus(content.versionId())).isEqualTo("PUBLISHED");
	}

	@Test
	void r1Ops001PlatformAdminHasAllAdmittedBusinessActionsButCannotSelfReview() throws Exception {
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);
		Operator admin = operator(AccountRole.PLATFORM_ADMIN);
		JsonNode created = postJson("/api/v1/ops/contents", Map.of(
				"title", "管理员业务权限", "body", "纯合成妈妈变化内容，用于验证管理员业务权限。",
				"targetStageCode", STAGE, "contentBasis", "SYNTHETIC_DEMO", "slotCode", "P20D0_MOM_CHANGE"),
				key(), editor.token(), 201);
		PublishedContent content = new PublishedContent(created.at("/data/contentId").asText(),
				created.at("/data/versionId").asText());

		JsonNode updated = putJson(versionPath(content), Map.of(
				"title", "管理员已修改内容", "body", "纯合成妈妈变化内容，管理员可修改已准入业务对象。",
				"targetStageCode", STAGE, "contentBasis", "SYNTHETIC_DEMO"), "1", key(), admin.token(), 200);
		assertThat(updated.at("/data/title").asText()).isEqualTo("管理员已修改内容");

		submit(editor, content);
		review(reviewer, content, "APPROVE");
		publish(admin, content);
		assertThat(contentStatus(content.versionId())).isEqualTo("PUBLISHED");

		JsonNode adminDraft = postJson("/api/v1/ops/contents", Map.of(
				"title", "管理员自审边界", "body", "纯合成内容，用于验证管理员也不能自审。",
				"targetStageCode", STAGE, "contentBasis", "SYNTHETIC_DEMO", "slotCode", "P20D1_MOM_CHANGE"),
				key(), admin.token(), 201);
		PublishedContent adminContent = new PublishedContent(adminDraft.at("/data/contentId").asText(),
				adminDraft.at("/data/versionId").asText());
		submit(admin, adminContent);
		JsonNode adminSelfReview = postJson(operationPath(adminContent, "review-decisions"),
				Map.of("decisionCode", "APPROVE"), key(), admin.token(), 409);
		assertError(adminSelfReview, "SELF_REVIEW_FORBIDDEN");
	}

	@Test
	void dec059CreatesAnIdempotentDraftSuccessorWithoutChangingThePublishedPointer() throws Exception {
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);
		Operator admin = operator(AccountRole.PLATFORM_ADMIN);
		Member member = defaultPerson();
		createProfile(member, "SYNTHETIC_PUBLISHED_REVISION");
		JsonNode created = postJson("/api/v1/ops/contents", Map.of(
				"title", "已发布的纯合成周刊", "body", "这是已发布源版本的纯合成正文。",
				"summary", "已发布源版本摘要", "targetStageCode", STAGE, "contentBasis", "SYNTHETIC_DEMO",
				"sourceReference", "SYNTHETIC_SOURCE_001", "sourceReviewDate", "2026-08-16"),
				key(), editor.token(), 201);
		PublishedContent source = new PublishedContent(created.at("/data/contentId").asText(),
				created.at("/data/versionId").asText());
		submit(editor, source);
		review(reviewer, source, "APPROVE");
		publish(reviewer, source);

		JsonNode anonymous = postJson(revisionDraftPath(source), Map.of("sourceVersionId", source.versionId()),
				null, null, 401);
		assertError(anonymous, "AUTHENTICATION_REQUIRED");
		JsonNode reviewerDenied = postJson(revisionDraftPath(source), Map.of("sourceVersionId", source.versionId()),
				key(), reviewer.token(), 403);
		assertError(reviewerDenied, "FORBIDDEN");

		JsonNode successor = postJson(revisionDraftPath(source), Map.of("sourceVersionId", source.versionId()),
				key(), editor.token(), 201);
		JsonNode replay = postJson(revisionDraftPath(source), Map.of("sourceVersionId", source.versionId()),
				key(), editor.token(), 201);
		JsonNode adminReplay = postJson(revisionDraftPath(source), Map.of("sourceVersionId", source.versionId()),
				key(), admin.token(), 201);
		PublishedContent successorContent = new PublishedContent(source.contentId(), successor.at("/data/versionId").asText());
		assertAll(
				() -> assertThat(successor.at("/data/versionNumber").asInt()).isEqualTo(2),
				() -> assertThat(successor.at("/data/status").asText()).isEqualTo("DRAFT"),
				() -> assertThat(successor.at("/data/title").asText()).isEqualTo(created.at("/data/title").asText()),
				() -> assertThat(successor.at("/data/body").asText()).isEqualTo(created.at("/data/body").asText()),
				() -> assertThat(successor.at("/data/summary").asText()).isEqualTo(created.at("/data/summary").asText()),
				() -> assertThat(successor.at("/data/targetStageCode").asText())
						.isEqualTo(created.at("/data/targetStageCode").asText()),
				() -> assertThat(successor.at("/data/contentBasis").asText())
						.isEqualTo(created.at("/data/contentBasis").asText()),
				() -> assertThat(successor.at("/data/sourceReference").asText())
						.isEqualTo(created.at("/data/sourceReference").asText()),
				() -> assertThat(successor.at("/data/sourceReviewDate").asText())
						.isEqualTo(created.at("/data/sourceReviewDate").asText()),
				() -> assertThat(successor.at("/data/syntheticDisplayLabel").asText())
						.isEqualTo(created.at("/data/syntheticDisplayLabel").asText()),
				() -> assertThat(successor.at("/data/professionalReviewStatus").asText())
						.isEqualTo(created.at("/data/professionalReviewStatus").asText()),
				() -> assertThat(replay.at("/data/versionId").asText()).isEqualTo(successorContent.versionId()),
				() -> assertThat(adminReplay.at("/data/versionId").asText()).isEqualTo(successorContent.versionId()),
				() -> assertThat(contentStatus(source.versionId())).isEqualTo("PUBLISHED"),
				() -> assertThat(jdbcClient.sql("SELECT current_published_version_id FROM content_item WHERE content_id = :contentId")
						.param("contentId", source.contentId()).query(String.class).single()).isEqualTo(source.versionId()),
				() -> assertThat(countVersions(source.contentId())).isEqualTo(2),
				() -> assertThat(getJson("/api/v1/public/contents/" + source.contentId(), member.token(), 200)
						.at("/data/versionId").asText()).isEqualTo(source.versionId()));

		submit(editor, successorContent);
		review(reviewer, successorContent, "APPROVE");
		publish(reviewer, successorContent);
		assertAll(
				() -> assertThat(contentStatus(source.versionId())).isEqualTo("PUBLISHED"),
				() -> assertThat(jdbcClient.sql("SELECT current_published_version_id FROM content_item WHERE content_id = :contentId")
						.param("contentId", source.contentId()).query(String.class).single()).isEqualTo(successorContent.versionId()),
				() -> assertThat(getJson("/api/v1/public/contents/" + source.contentId(), member.token(), 200)
						.at("/data/versionId").asText()).isEqualTo(successorContent.versionId()));
	}

	@Test
	void dec059RejectsCompetingSuccessorsAndOnlyAcceptsTheCurrentPublishedSource() throws Exception {
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);
		Member member = defaultPerson();
		createProfile(member, "SYNTHETIC_SUCCESSOR_VISIBILITY");
		PublishedContent source = publishContent(editor, reviewer, "后继草稿源版本", "用于并发边界验证的纯合成正文。");
		JsonNode absentSource = postJson(revisionDraftPath(source), Map.of("sourceVersionId", Identifiers.newId()),
				key(), editor.token(), 409);
		assertError(absentSource, "SOURCE_VERSION_NOT_CURRENT");
		PublishedContent otherContent = publishContent(editor, reviewer, "其他内容版本", "用于跨内容源版本边界验证的纯合成正文。");
		JsonNode crossContentSource = postJson(revisionDraftPath(source), Map.of("sourceVersionId", otherContent.versionId()),
				key(), editor.token(), 409);
		assertError(crossContentSource, "SOURCE_VERSION_NOT_CURRENT");
		JsonNode successor = postJson(revisionDraftPath(source), Map.of("sourceVersionId", source.versionId()),
				key(), editor.token(), 201);
		PublishedContent successorContent = new PublishedContent(source.contentId(), successor.at("/data/versionId").asText());
		submit(editor, successorContent);
		assertThat(getJson("/api/v1/public/contents/" + source.contentId(), member.token(), 200)
				.at("/data/versionId").asText()).isEqualTo(source.versionId());

		JsonNode inProgress = postJson(revisionDraftPath(source), Map.of("sourceVersionId", source.versionId()),
				key(), editor.token(), 409);
		assertAll(
				() -> assertError(inProgress, "CONTENT_REVISION_ALREADY_IN_PROGRESS"),
				() -> assertThat(countVersions(source.contentId())).isEqualTo(2),
				() -> assertThat(jdbcClient.sql("SELECT current_published_version_id FROM content_item WHERE content_id = :contentId")
						.param("contentId", source.contentId()).query(String.class).single()).isEqualTo(source.versionId()));

		review(reviewer, successorContent, "APPROVE");
		JsonNode approvedConflict = postJson(revisionDraftPath(source), Map.of("sourceVersionId", source.versionId()),
				key(), editor.token(), 409);
		assertAll(
				() -> assertError(approvedConflict, "CONTENT_REVISION_ALREADY_IN_PROGRESS"),
				() -> assertThat(countVersions(source.contentId())).isEqualTo(2),
				() -> assertThat(jdbcClient.sql("SELECT current_published_version_id FROM content_item WHERE content_id = :contentId")
						.param("contentId", source.contentId()).query(String.class).single()).isEqualTo(source.versionId()),
				() -> assertThat(getJson("/api/v1/public/contents/" + source.contentId(), member.token(), 200)
						.at("/data/versionId").asText()).isEqualTo(source.versionId()));
		publish(reviewer, successorContent);
		JsonNode staleSource = postJson(revisionDraftPath(source), Map.of("sourceVersionId", source.versionId()),
				key(), editor.token(), 409);
		assertError(staleSource, "SOURCE_VERSION_NOT_CURRENT");

		JsonNode nextDraft = postJson(revisionDraftPath(source), Map.of("sourceVersionId", successorContent.versionId()),
				key(), editor.token(), 201);
		assertAll(
				() -> assertThat(nextDraft.at("/data/versionNumber").asInt()).isEqualTo(3),
				() -> assertThat(nextDraft.at("/data/status").asText()).isEqualTo("DRAFT"),
				() -> assertThat(countVersions(source.contentId())).isEqualTo(3));
		PublishedContent rejectedSuccessor = new PublishedContent(source.contentId(), nextDraft.at("/data/versionId").asText());
		submit(editor, rejectedSuccessor);
		review(reviewer, rejectedSuccessor, "REJECT_OUT_OF_SCOPE");
		JsonNode rejectedConflict = postJson(revisionDraftPath(source),
				Map.of("sourceVersionId", successorContent.versionId()), key(), editor.token(), 409);
		assertAll(
				() -> assertError(rejectedConflict, "CONTENT_REVISION_ALREADY_IN_PROGRESS"),
				() -> assertThat(countVersions(source.contentId())).isEqualTo(3));
	}

	@Test
	void dec059ConcurrentRequestsReuseOneDraftSuccessorAndPreserveThePublishedPointer() throws Exception {
		Operator editor = operator(AccountRole.CONTENT_EDITOR);
		Operator reviewer = operator(AccountRole.CONTENT_REVIEWER);
		Member member = defaultPerson();
		createProfile(member, "SYNTHETIC_CONCURRENT_SUCCESSOR");
		PublishedContent source = publishContent(editor, reviewer, "并发后继草稿源版本", "用于并发后继草稿验证的纯合成正文。");

		List<JsonNode> responses = concurrentSuccessorDraftRequests(source, editor);
		assertAll(
				() -> assertThat(responses).hasSize(2),
				() -> assertThat(responses.get(0).at("/data/versionId").asText())
						.isEqualTo(responses.get(1).at("/data/versionId").asText()),
				() -> assertThat(responses.get(0).at("/data/versionNumber").asInt()).isEqualTo(2),
				() -> assertThat(countVersions(source.contentId())).isEqualTo(2),
				() -> assertThat(jdbcClient.sql("SELECT current_published_version_id FROM content_item WHERE content_id = :contentId")
						.param("contentId", source.contentId()).query(String.class).single()).isEqualTo(source.versionId()),
				() -> assertThat(getJson("/api/v1/public/contents/" + source.contentId(), member.token(), 200)
						.at("/data/versionId").asText()).isEqualTo(source.versionId()));
	}

	private Operator operator(AccountRole role) throws Exception {
		String accountId = Identifiers.newId();
		String username = switch (role) {
			case CONTENT_EDITOR -> "editor" + suffix();
			case CONTENT_REVIEWER -> "reviewer" + suffix();
			case PLATFORM_ADMIN -> "admin" + suffix();
		};
		String password = "SyntheticOps!" + suffix();
		jdbcClient.sql("""
				INSERT INTO synthetic_account
				(account_id, username_normalized, password_hash, account_type, status, profile_status, synthetic, created_at)
				VALUES (:accountId, :username, :passwordHash, :accountType, 'ACTIVE', 'REQUIRED', TRUE, :createdAt)
				""")
				.param("accountId", accountId)
				.param("username", username)
				.param("passwordHash", passwordEncoder.encode(password))
				.param("accountType", role.name())
				.param("createdAt", Timestamp.from(Instant.now()))
				.update();
		String loginKey = key();
		int sessionsBefore = count("synthetic_session");
		JsonNode login = postJson("/api/v1/synthetic-auth/sessions", Map.of("username", username, "password", password),
				loginKey, null, 201);
		JsonNode replay = postJson("/api/v1/synthetic-auth/sessions", Map.of("username", username, "password", password),
				loginKey, null, 201);
		assertThat(replay.at("/data/sessionToken").asText()).isEqualTo(login.at("/data/sessionToken").asText());
		assertThat(login.at("/data/role").asText()).isEqualTo(role.name());
		assertThat(login.at("/data/profileStatus").asText()).isEqualTo("REQUIRED");
		assertThat(count("synthetic_session")).isEqualTo(sessionsBefore + 1);
		return new Operator(accountId, login.at("/data/sessionToken").asText(), role,
				login.at("/data/accountDisplayId").asText());
	}

	private Member defaultPerson() throws Exception {
		return personSession(DEFAULT_PERSON_REFERENCE);
	}

	private Member additionalPerson(String personReference) throws Exception {
		insertNaturalPerson(Identifiers.newId(), personReference);
		return personSession(personReference);
	}

	private Member personSession(String personReference) throws Exception {
		JsonNode result = postIdentity(Map.of(), IDENTITY_PROVIDER, personReference, IDENTITY_ASSERTION,
				"127.0.0.1", 201);
		return new Member(result.at("/data/personId").asText(), result.at("/data/sessionToken").asText(),
				result.at("/data/userDisplayId").asText());
	}

	private String createProfile(Member member, String alias) throws Exception {
		JsonNode result = profileAttempt(member, profilePayload(alias, 143), 201);
		return result.at("/data/subjectId").asText();
	}

	private static Map<String, Object> profilePayload(String alias, int totalGestationalDays) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("subjectType", "PREGNANT_WOMAN");
		payload.put("syntheticAlias", alias);
		payload.put("serviceMode", "PREGNANCY");
		payload.put("stageCode", STAGE);
		payload.put("stageBusinessTime", Instant.now().minusSeconds(5).toString());
		payload.put("lastMenstrualPeriodDate",
				LocalDate.now(BUSINESS_ZONE).minusDays(totalGestationalDays).toString());
		payload.put("averageMenstrualDurationDays", 5);
		payload.put("synthetic", true);
		return payload;
	}

	private JsonNode profileAttempt(Member member, Map<String, Object> payload, int expectedStatus) throws Exception {
		return postJson("/api/v1/me/health-subjects/self", payload, key(), member.token(), expectedStatus);
	}

	private PublishedContent publishContent(Operator editor, Operator reviewer, String title, String body) throws Exception {
		PublishedContent content = createDraft(editor, title, body);
		submit(editor, content);
		review(reviewer, content, "APPROVE");
		publish(reviewer, content);
		return content;
	}

	private PublishedContent publishContentAtSlot(Operator editor, Operator reviewer, String title, String body,
			String slotCode) throws Exception {
		PublishedContent content = createDraftAtSlot(editor, title, body, slotCode);
		submit(editor, content);
		review(reviewer, content, "APPROVE");
		publish(reviewer, content);
		return content;
	}

	private PublishedContent createDraftAtSlot(Operator editor, String title, String body, String slotCode)
			throws Exception {
		JsonNode result = postJson("/api/v1/ops/contents", Map.of(
				"title", title, "body", body, "targetStageCode", STAGE,
				"contentBasis", "SYNTHETIC_DEMO", "slotCode", slotCode), key(), editor.token(), 201);
		return new PublishedContent(result.at("/data/contentId").asText(),
				result.at("/data/versionId").asText());
	}

	private PublishedContent createDraft(Operator editor, String title, String body) throws Exception {
		JsonNode result = postJson("/api/v1/ops/contents", Map.of("title", title, "body", body,
				"targetStageCode", STAGE, "contentBasis", "SYNTHETIC_DEMO"), key(), editor.token(), 201);
		return new PublishedContent(result.at("/data/contentId").asText(), result.at("/data/versionId").asText());
	}

	private void submit(Operator editor, PublishedContent content) throws Exception {
		JsonNode result = postJson(operationPath(content, "submit"), Map.of(), key(), editor.token(), 200);
		assertThat(result.at("/data/status").asText()).isEqualTo("IN_REVIEW");
	}

	private void review(Operator reviewer, PublishedContent content, String decision) throws Exception {
		JsonNode result = postJson(operationPath(content, "review-decisions"), Map.of("decisionCode", decision),
				key(), reviewer.token(), 200);
		assertThat(result.at("/data/status").asText()).isEqualTo("APPROVE".equals(decision) ? "APPROVED" : "REJECTED");
	}

	private void publish(Operator reviewer, PublishedContent content) throws Exception {
		JsonNode result = postJson(operationPath(content, "publish"), Map.of(), key(), reviewer.token(), 200);
		assertThat(result.at("/data/status").asText()).isEqualTo("PUBLISHED");
	}

	private void assertUnifiedNotPublished(String contentId, String token) throws Exception {
		JsonNode response = getJson("/api/v1/public/contents/" + contentId, token, 404);
		assertError(response, "CONTENT_NOT_FOUND_OR_NOT_PUBLISHED");
		assertThat(response.toString()).doesNotContain(contentId).doesNotContain("DRAFT").doesNotContain("IN_REVIEW")
				.doesNotContain("APPROVED").doesNotContain("REJECTED");
	}

	private void assertProfileCounts(int expected) {
		assertAll(
				() -> assertThat(count("health_subject")).isEqualTo(expected),
				() -> assertThat(count("person_subject_relation")).isEqualTo(expected),
				() -> assertThat(count("account_subject_relation")).isZero(),
				() -> assertThat(count("explicit_stage_profile")).isEqualTo(expected));
	}

	private void assertNaturalPersonSchemaHasOnlyApprovedDisplayAttributes() {
		Set<String> columns = Set.copyOf(jdbcClient.sql("""
				SELECT LOWER(column_name) FROM information_schema.columns
				WHERE table_schema = DATABASE() AND table_name = 'natural_person'
				""").query(String.class).list());
		assertThat(columns).containsExactlyInAnyOrder("person_id", "identity_provider", "external_person_reference",
				"display_name", "date_of_birth", "status", "profile_status", "synthetic", "created_at", "updated_at");
		assertThat(columns).doesNotContain("name", "full_name", "age", "national_id", "id_card_number",
				"phone", "phone_number", "mobile");
	}

	private void insertNaturalPerson(String personId, String personReference) {
		Instant now = Instant.now();
		jdbcClient.sql("""
				INSERT INTO natural_person
				(person_id, identity_provider, external_person_reference, display_name, date_of_birth,
				 status, profile_status, synthetic, created_at, updated_at)
				VALUES (:personId, :identityProvider, :personReference, :displayName, :dateOfBirth,
				 'ACTIVE', 'REQUIRED', TRUE, :createdAt, :updatedAt)
				""")
				.param("personId", personId)
				.param("identityProvider", IDENTITY_PROVIDER)
				.param("personReference", personReference)
				.param("displayName", DEFAULT_DISPLAY_NAME)
				.param("dateOfBirth", java.sql.Date.valueOf(DEFAULT_DATE_OF_BIRTH))
				.param("createdAt", Timestamp.from(now))
				.param("updatedAt", Timestamp.from(now))
				.update();
	}

	private int count(String table) {
		return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
	}

	private String contentStatus(String versionId) {
		return jdbcClient.sql("SELECT status FROM content_version WHERE version_id = :versionId")
				.param("versionId", versionId).query(String.class).single();
	}

	private int countVersions(String contentId) {
		return jdbcClient.sql("SELECT COUNT(*) FROM content_version WHERE content_id = :contentId")
				.param("contentId", contentId).query(Integer.class).single();
	}

	private List<JsonNode> concurrentSuccessorDraftRequests(PublishedContent source, Operator editor) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(3);
		CountDownLatch sourceLockAcquired = new CountDownLatch(1);
		CountDownLatch releaseSourceLock = new CountDownLatch(1);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		Future<?> sourceLockHolder = executor.submit(() -> transaction.executeWithoutResult(status -> {
			jdbcClient.sql("SELECT version_id FROM content_version WHERE content_id = :contentId AND version_id = :versionId FOR UPDATE")
					.param("contentId", source.contentId()).param("versionId", source.versionId()).query(String.class).single();
			sourceLockAcquired.countDown();
			try {
				if (!releaseSourceLock.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Timed out waiting to release source version lock.");
				}
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while holding source version lock.", exception);
			}
		}));
		CyclicBarrier startBarrier = new CyclicBarrier(2);
		try {
			if (!sourceLockAcquired.await(10, TimeUnit.SECONDS)) {
				throw new AssertionError("Test source version lock was not acquired.");
			}
			Future<JsonNode> first = executor.submit(() -> {
				startBarrier.await(10, TimeUnit.SECONDS);
				return postJson(revisionDraftPath(source), Map.of("sourceVersionId", source.versionId()),
						key(), editor.token(), 201);
			});
			Future<JsonNode> second = executor.submit(() -> {
				startBarrier.await(10, TimeUnit.SECONDS);
				return postJson(revisionDraftPath(source), Map.of("sourceVersionId", source.versionId()),
						key(), editor.token(), 201);
			});
			assertThat(anyRequestCompletedWhileSourceLockHeld(first, second)).isFalse();
			releaseSourceLock.countDown();
			sourceLockHolder.get(10, TimeUnit.SECONDS);
			return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
		}
		finally {
			releaseSourceLock.countDown();
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	private static boolean anyRequestCompletedWhileSourceLockHeld(Future<?> first, Future<?> second)
			throws InterruptedException {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (System.nanoTime() < deadlineNanos) {
			if (first.isDone() || second.isDone()) {
				return true;
			}
			Thread.sleep(10);
		}
		return false;
	}

	private static String weightPath(String subjectId) {
		return "/api/v1/me/health-subjects/" + subjectId + "/weights";
	}

	private static String versionPath(PublishedContent content) {
		return "/api/v1/ops/contents/" + content.contentId() + "/versions/" + content.versionId();
	}

	private static String operationPath(PublishedContent content, String operation) {
		return versionPath(content) + "/" + operation;
	}

	private static String revisionDraftPath(PublishedContent content) {
		return "/api/v1/ops/contents/" + content.contentId() + "/versions";
	}

	private static Map<String, Object> weightPayload(String value, Instant businessTime) {
		return Map.of("value", value, "unit", "KG", "businessTime", businessTime.toString(),
				"sourceType", "USER_SELF_REPORTED", "synthetic", true);
	}

	private JsonNode getJson(String path, String token, int expectedStatus) throws Exception {
		MockHttpServletRequestBuilder builder = get(path).accept(MediaType.APPLICATION_JSON);
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return perform(builder, expectedStatus);
	}

	private JsonNode postJson(String path, Object body, String idempotencyKey, String token, int expectedStatus) throws Exception {
		MockHttpServletRequestBuilder builder = post(path).contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		if (idempotencyKey != null) {
			builder.header("Idempotency-Key", idempotencyKey);
		}
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return perform(builder, expectedStatus);
	}

	private JsonNode postIdentity(Object body, String identityProvider, String personReference,
			String identityAssertion, String remoteAddress, int expectedStatus) throws Exception {
		MockHttpServletRequestBuilder builder = post("/api/v1/public-identity/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body))
				.with(request -> {
					request.setRemoteAddr(remoteAddress);
					return request;
				});
		if (identityProvider != null) {
			builder.header("X-Trusted-Identity-Provider", identityProvider);
		}
		if (personReference != null) {
			builder.header("X-Trusted-Person-Reference", personReference);
		}
		if (identityAssertion != null) {
			builder.header("X-Trusted-Identity-Assertion", identityAssertion);
		}
		return perform(builder, expectedStatus);
	}

	private JsonNode putJson(String path, Object body, String ifMatch, String idempotencyKey, String token,
			int expectedStatus) throws Exception {
		MockHttpServletRequestBuilder builder = put(path).contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
				.header("If-Match", ifMatch).header("Idempotency-Key", idempotencyKey)
				.header("Authorization", "Bearer " + token);
		return perform(builder, expectedStatus);
	}

	private JsonNode perform(MockHttpServletRequestBuilder builder, int expectedStatus) throws Exception {
		MvcResult result = mockMvc.perform(builder).andExpect(status().is(expectedStatus)).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	private static void assertError(JsonNode response, String expectedCode) {
		assertAll(
				() -> assertThat(response.at("/error/code").asText()).isEqualTo(expectedCode),
				() -> assertThat(response.at("/error/requestId").asText()).isNotBlank(),
				() -> assertThat(response.at("/error/fieldErrors").isArray()).isTrue());
	}

	private static String key() {
		return "idem_" + UUID.randomUUID().toString().replace("-", "");
	}

	private static String suffix() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	private record Member(String personId, String token, String displayId) {
	}

	private record Operator(String accountId, String token, AccountRole role, String displayId) {
	}

	private record PublishedContent(String contentId, String versionId) {
	}
}
