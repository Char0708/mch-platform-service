package io.github.char0708.mch.platform.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.char0708.mch.platform.audit.AuditService;
import io.github.char0708.mch.platform.shared.ApiException;
import io.github.char0708.mch.platform.shared.Hashing;
import io.github.char0708.mch.platform.shared.IdempotencyService;
import io.github.char0708.mch.platform.shared.Identifiers;

@Service
public class SyntheticAuthService {

	private static final String SESSION_ENDPOINT = "POST:/api/v1/synthetic-auth/sessions";

	private final JdbcClient jdbcClient;
	private final PasswordEncoder passwordEncoder;
	private final SyntheticTokenCodec tokenCodec;
	private final IdempotencyService idempotencyService;
	private final AuditService auditService;

	SyntheticAuthService(JdbcClient jdbcClient, PasswordEncoder passwordEncoder,
			SyntheticTokenCodec tokenCodec, IdempotencyService idempotencyService, AuditService auditService) {
		this.jdbcClient = jdbcClient;
		this.passwordEncoder = passwordEncoder;
		this.tokenCodec = tokenCodec;
		this.idempotencyService = idempotencyService;
		this.auditService = auditService;
	}

	@Transactional
	public AuthResult login(LoginCommand command, String idempotencyKey, String requestId) {
		String key = idempotencyService.requireValidKey(idempotencyKey);
		String username = normalizeUsername(command.username());
		if (command.password() == null) {
			throw authenticationFailed();
		}
		String payloadHash = Hashing.sha256(username);
		Optional<IdempotencyService.Replay> replay = idempotencyService.find("PUBLIC_LOGIN", SESSION_ENDPOINT,
				key, payloadHash);
		if (replay.isPresent()) {
			AuthResult result = resultForSession(replay.get().resourceId());
			if (!passwordEncoder.matches(command.password(), accountById(result.accountId()).passwordHash())) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"该幂等键已用于不同请求。");
			}
			return result;
		}
		AccountRow account = accountByUsername(username)
				.filter(row -> passwordEncoder.matches(command.password(), row.passwordHash()))
				.orElseThrow(SyntheticAuthService::authenticationFailed);
		Instant now = Instant.now();
		String sessionId = Identifiers.newId();
		jdbcClient.sql("""
				INSERT INTO synthetic_session (session_id, account_id, created_at, expires_at)
				VALUES (:sessionId, :accountId, :createdAt, :expiresAt)
				""")
				.param("sessionId", sessionId)
				.param("accountId", account.accountId())
				.param("createdAt", Timestamp.from(now))
				.param("expiresAt", Timestamp.from(now.plus(12, ChronoUnit.HOURS)))
				.update();
		idempotencyService.record("PUBLIC_LOGIN", SESSION_ENDPOINT, key, payloadHash, "SESSION", sessionId, 201, now);
		auditService.success("LOGIN_SUCCEEDED", account.accountId(), account.role().name(), "LOGIN",
				"ACCOUNT", account.accountId(), null, null, null, null, requestId);
		return new AuthResult(account.accountId(), tokenCodec.issue(sessionId), account.role(), account.profileStatus(), true);
	}

	public SessionActor authenticate(HttpServletRequest request, AccountRole... allowedRoles) {
		String authorization = request.getHeader("Authorization");
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			throw authenticationRequired();
		}
		String sessionId = tokenCodec.verifyAndExtract(authorization.substring(7))
				.orElseThrow(SyntheticAuthService::authenticationRequired);
		Optional<SessionActor> actor = jdbcClient.sql("""
				SELECT a.account_id, a.account_type, a.profile_status
				FROM synthetic_session s
				JOIN synthetic_account a ON a.account_id = s.account_id
				WHERE s.session_id = :sessionId AND s.expires_at > :now
				  AND a.status = 'ACTIVE' AND a.synthetic = TRUE
				  AND a.account_type IN ('CONTENT_EDITOR', 'CONTENT_REVIEWER', 'PLATFORM_ADMIN')
				""")
				.param("sessionId", sessionId)
				.param("now", Timestamp.from(Instant.now()))
				.query((resultSet, rowNumber) -> new SessionActor(resultSet.getString("account_id"),
						AccountRole.valueOf(resultSet.getString("account_type")), resultSet.getString("profile_status")))
				.optional();
		SessionActor resolved = actor.orElseThrow(SyntheticAuthService::authenticationRequired);
		request.setAttribute(SessionActor.REQUEST_ATTRIBUTE, resolved);
		if (allowedRoles.length > 0 && Arrays.stream(allowedRoles).noneMatch(resolved.role()::equals)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前运营角色无权执行此操作。");
		}
		return resolved;
	}

	private Optional<AccountRow> accountByUsername(String username) {
		return jdbcClient.sql("""
				SELECT account_id, password_hash, account_type, profile_status
				FROM synthetic_account
				WHERE username_normalized = :username AND status = 'ACTIVE' AND synthetic = TRUE
				  AND account_type IN ('CONTENT_EDITOR', 'CONTENT_REVIEWER', 'PLATFORM_ADMIN')
				""")
				.param("username", username)
				.query((resultSet, rowNumber) -> new AccountRow(resultSet.getString("account_id"),
						resultSet.getString("password_hash"), AccountRole.valueOf(resultSet.getString("account_type")),
						resultSet.getString("profile_status")))
				.optional();
	}

	private AccountRow accountById(String accountId) {
		return jdbcClient.sql("""
				SELECT account_id, password_hash, account_type, profile_status
				FROM synthetic_account
				WHERE account_id = :accountId AND status = 'ACTIVE' AND synthetic = TRUE
				  AND account_type IN ('CONTENT_EDITOR', 'CONTENT_REVIEWER', 'PLATFORM_ADMIN')
				""")
				.param("accountId", accountId)
				.query((resultSet, rowNumber) -> new AccountRow(resultSet.getString("account_id"),
						resultSet.getString("password_hash"), AccountRole.valueOf(resultSet.getString("account_type")),
						resultSet.getString("profile_status")))
				.single();
	}

	private AuthResult resultForSession(String sessionId) {
		return jdbcClient.sql("""
				SELECT a.account_id, a.account_type, a.profile_status
				FROM synthetic_session s
				JOIN synthetic_account a ON a.account_id = s.account_id
				WHERE s.session_id = :sessionId AND a.status = 'ACTIVE' AND a.synthetic = TRUE
				  AND a.account_type IN ('CONTENT_EDITOR', 'CONTENT_REVIEWER', 'PLATFORM_ADMIN')
				""")
				.param("sessionId", sessionId)
				.query((resultSet, rowNumber) -> new AuthResult(resultSet.getString("account_id"),
						tokenCodec.issue(sessionId), AccountRole.valueOf(resultSet.getString("account_type")),
						resultSet.getString("profile_status"), true))
				.optional()
				.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"幂等结果不可用。"));
	}

	private static String normalizeUsername(String raw) {
		return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
	}

	private static ApiException authenticationRequired() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "需要有效的运营账户会话。");
	}

	private static ApiException authenticationFailed() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "运营账号或密码不匹配。");
	}

	public record LoginCommand(String username, String password) {
	}

	public record AuthResult(String accountId, String sessionToken, AccountRole role, String profileStatus,
			boolean synthetic) {
	}

	private record AccountRow(String accountId, String passwordHash, AccountRole role, String profileStatus) {
	}
}
