package io.github.char0708.mch.platform.identity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.char0708.mch.platform.audit.AuditService;
import io.github.char0708.mch.platform.auth.SyntheticTokenCodec;
import io.github.char0708.mch.platform.shared.ApiException;
import io.github.char0708.mch.platform.shared.Identifiers;

@Service
public class PublicIdentityService {

	private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hong_Kong");

	private final JdbcClient jdbcClient;
	private final SyntheticTokenCodec tokenCodec;
	private final AuditService auditService;
	private final boolean fixtureEnabled;
	private final String expectedProvider;
	private final String expectedAssertion;

	PublicIdentityService(JdbcClient jdbcClient, SyntheticTokenCodec tokenCodec, AuditService auditService,
			@Value("${mch.public-identity.synthetic-fixture.enabled:false}") boolean fixtureEnabled,
			@Value("${mch.public-identity.synthetic-fixture.provider:R1_SYNTHETIC_FIXTURE}") String expectedProvider,
			@Value("${mch.public-identity.synthetic-fixture.shared-assertion:}") String expectedAssertion) {
		this.jdbcClient = jdbcClient;
		this.tokenCodec = tokenCodec;
		this.auditService = auditService;
		this.fixtureEnabled = fixtureEnabled;
		this.expectedProvider = expectedProvider;
		this.expectedAssertion = expectedAssertion;
	}

	@Transactional
	public IdentitySessionResult establishSession(HttpServletRequest request, String identityProvider,
			String personReference, String identityAssertion, String requestId) {
		if (!fixtureEnabled || !isLoopback(request.getRemoteAddr()) || expectedAssertion.length() < 24
				|| !constantTimeEquals(expectedProvider, identityProvider)
				|| !constantTimeEquals(expectedAssertion, identityAssertion)
				|| personReference == null || !personReference.matches("[A-Za-z0-9._:-]{1,128}")) {
			throw authenticationFailed();
		}

		PersonRow person = jdbcClient.sql("""
				SELECT p.person_id, p.profile_status, p.display_name, p.date_of_birth, r.subject_id
				FROM natural_person p
				LEFT JOIN person_subject_relation r
				  ON r.person_id = p.person_id AND r.relation_type = 'SELF'
				 AND r.status = 'ACTIVE' AND r.valid_to IS NULL
				WHERE p.identity_provider = :identityProvider
				  AND p.external_person_reference = :personReference
				  AND p.status = 'ACTIVE' AND p.synthetic = TRUE
				""")
				.param("identityProvider", identityProvider)
				.param("personReference", personReference)
				.query((resultSet, rowNumber) -> new PersonRow(resultSet.getString("person_id"),
						resultSet.getString("profile_status"), resultSet.getString("display_name"),
						resultSet.getObject("date_of_birth", LocalDate.class), resultSet.getString("subject_id")))
				.optional()
				.orElseThrow(PublicIdentityService::authenticationFailed);

		if (("COMPLETE".equals(person.profileStatus())) != (person.subjectId() != null)) {
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "身份档案状态不一致。");
		}
		Instant now = Instant.now();
		LocalDate businessDate = now.atZone(BUSINESS_ZONE).toLocalDate();
		if (person.displayName() == null || person.displayName().isBlank() || person.dateOfBirth() == null
				|| person.dateOfBirth().isAfter(businessDate)) {
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "身份属性不完整。");
		}
		int ageYears = Period.between(person.dateOfBirth(), businessDate).getYears();

		String sessionId = Identifiers.newId();
		jdbcClient.sql("""
				INSERT INTO public_person_session
				(session_id, person_id, identity_provider, created_at, expires_at)
				VALUES (:sessionId, :personId, :identityProvider, :createdAt, :expiresAt)
				""")
				.param("sessionId", sessionId)
				.param("personId", person.personId())
				.param("identityProvider", identityProvider)
				.param("createdAt", Timestamp.from(now))
				.param("expiresAt", Timestamp.from(now.plus(4, ChronoUnit.HOURS)))
				.update();
		auditService.success("PUBLIC_IDENTITY_SESSION_ISSUED", person.personId(), NaturalPersonActor.PRINCIPAL_TYPE,
				"ESTABLISH_PUBLIC_IDENTITY_SESSION", "NATURAL_PERSON", person.personId(), null, "ACTIVE",
				null, null, requestId);
		return new IdentitySessionResult(person.personId(), Identifiers.userDisplayId(person.personId()),
				tokenCodec.issue(sessionId), NaturalPersonActor.PRINCIPAL_TYPE, person.profileStatus(), person.subjectId(),
				person.displayName(), ageYears, true);
	}

	public NaturalPersonActor authenticate(HttpServletRequest request) {
		String authorization = request.getHeader("Authorization");
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			throw authenticationRequired();
		}
		String sessionId = tokenCodec.verifyAndExtract(authorization.substring(7))
				.orElseThrow(PublicIdentityService::authenticationRequired);
		Optional<NaturalPersonActor> actor = jdbcClient.sql("""
				SELECT p.person_id, p.profile_status
				FROM public_person_session s
				JOIN natural_person p ON p.person_id = s.person_id
				WHERE s.session_id = :sessionId AND s.expires_at > :now
				  AND p.status = 'ACTIVE' AND p.synthetic = TRUE
				""")
				.param("sessionId", sessionId)
				.param("now", Timestamp.from(Instant.now()))
				.query((resultSet, rowNumber) -> new NaturalPersonActor(resultSet.getString("person_id"),
						resultSet.getString("profile_status")))
				.optional();
		NaturalPersonActor resolved = actor.orElseThrow(PublicIdentityService::authenticationRequired);
		request.setAttribute(NaturalPersonActor.REQUEST_ATTRIBUTE, resolved);
		return resolved;
	}

	private static boolean constantTimeEquals(String expected, String actual) {
		if (expected == null || actual == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}

	private static boolean isLoopback(String remoteAddress) {
		if (remoteAddress == null || remoteAddress.length() > 64) {
			return false;
		}
		try {
			return InetAddress.getByName(remoteAddress).isLoopbackAddress();
		}
		catch (UnknownHostException exception) {
			return false;
		}
	}

	private static ApiException authenticationRequired() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "需要有效的身份会话。");
	}

	private static ApiException authenticationFailed() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "身份上下文无法验证。");
	}

	public record IdentitySessionResult(String personId, String userDisplayId, String sessionToken,
			String principalType, String profileStatus, String subjectId, String displayName,
			int ageYears, boolean synthetic) {
	}

	private record PersonRow(String personId, String profileStatus, String displayName,
			LocalDate dateOfBirth, String subjectId) {
	}
}
