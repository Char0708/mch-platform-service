package io.github.char0708.mch.platform.shared;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {

	private final JdbcClient jdbcClient;

	IdempotencyService(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public String requireValidKey(String key) {
		if (key == null || key.isBlank() || key.length() > 128) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "需要有效的幂等键。",
					java.util.List.of(new ApiException.FieldError("Idempotency-Key", "REQUIRED_OR_INVALID")));
		}
		return key;
	}

	public Optional<Replay> find(String actorScope, String endpointScope, String key, String payloadHash) {
		Optional<Replay> replay = jdbcClient.sql("""
				SELECT payload_hash, resource_type, resource_id, response_status
				FROM idempotency_record
				WHERE actor_scope = :actorScope AND endpoint_scope = :endpointScope AND idempotency_key = :idempotencyKey
				""")
				.param("actorScope", actorScope)
				.param("endpointScope", endpointScope)
				.param("idempotencyKey", key)
				.query((resultSet, rowNumber) -> new Replay(resultSet.getString("payload_hash"),
						resultSet.getString("resource_type"), resultSet.getString("resource_id"),
						resultSet.getInt("response_status")))
				.optional();
		if (replay.isPresent() && !MessageDigestSupport.constantTimeEquals(replay.get().payloadHash(), payloadHash)) {
			throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "该幂等键已用于不同请求。 ");
		}
		return replay;
	}

	public void record(String actorScope, String endpointScope, String key, String payloadHash,
			String resourceType, String resourceId, int responseStatus, Instant createdAt) {
		jdbcClient.sql("""
				INSERT INTO idempotency_record
				(actor_scope, endpoint_scope, idempotency_key, payload_hash, resource_type, resource_id, response_status, created_at)
				VALUES (:actorScope, :endpointScope, :idempotencyKey, :payloadHash, :resourceType, :resourceId, :responseStatus, :createdAt)
				""")
				.param("actorScope", actorScope)
				.param("endpointScope", endpointScope)
				.param("idempotencyKey", key)
				.param("payloadHash", payloadHash)
				.param("resourceType", resourceType)
				.param("resourceId", resourceId)
				.param("responseStatus", responseStatus)
				.param("createdAt", Timestamp.from(createdAt))
				.update();
	}

	public record Replay(String payloadHash, String resourceType, String resourceId, int responseStatus) {
	}

	private static final class MessageDigestSupport {

		private MessageDigestSupport() {
		}

		static boolean constantTimeEquals(String left, String right) {
			return java.security.MessageDigest.isEqual(left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
					right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		}
	}
}
