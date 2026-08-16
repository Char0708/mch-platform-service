package io.github.char0708.mch.platform.audit;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.char0708.mch.platform.shared.Identifiers;

@Service
public class AuditService {

	private final JdbcClient jdbcClient;

	AuditService(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Transactional
	public void success(String eventName, String actorId, String actorRole, String actionName,
			String resourceType, String resourceId, String fromStatus, String toStatus,
			String decisionCode, String metricEventType, String traceId) {
		insert(eventName, actorId, actorRole, actionName, resourceType, resourceId, "SUCCESS", null,
				fromStatus, toStatus, decisionCode, metricEventType, traceId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failure(String actorId, String actorRole, String actionName, String resourceType,
			String resourceId, String errorCode, String traceId, boolean metricRequest) {
		String eventName = switch (errorCode) {
			case "FORBIDDEN", "SELF_REVIEW_FORBIDDEN" -> "AUTHORIZATION_DENIED";
			case "DUPLICATE_WEIGHT_RECORD", "IDEMPOTENCY_KEY_REUSED", "USERNAME_ALREADY_EXISTS" -> "DUPLICATE_REJECTED";
			default -> "REQUEST_REJECTED";
		};
		insert(eventName, metricRequest ? null : actorId, metricRequest ? null : actorRole, actionName,
				resourceType, resourceId, "FAILURE", errorCode, null, null, null, null, traceId);
	}

	private void insert(String eventName, String actorId, String actorRole, String actionName,
			String resourceType, String resourceId, String resultStatus, String errorCode,
			String fromStatus, String toStatus, String decisionCode, String metricEventType, String traceId) {
		jdbcClient.sql("""
				INSERT INTO synthetic_audit_event
				(audit_id, event_name, actor_id, actor_role, action_name, resource_type, resource_id,
				 result_status, error_code, from_status, to_status, decision_code, metric_event_type, trace_id, occurred_at)
				VALUES (:auditId, :eventName, :actorId, :actorRole, :actionName, :resourceType, :resourceId,
				 :resultStatus, :errorCode, :fromStatus, :toStatus, :decisionCode, :metricEventType, :traceId, :occurredAt)
				""")
				.param("auditId", Identifiers.newId())
				.param("eventName", eventName)
				.param("actorId", actorId)
				.param("actorRole", actorRole)
				.param("actionName", actionName)
				.param("resourceType", resourceType)
				.param("resourceId", resourceId)
				.param("resultStatus", resultStatus)
				.param("errorCode", errorCode)
				.param("fromStatus", fromStatus)
				.param("toStatus", toStatus)
				.param("decisionCode", decisionCode)
				.param("metricEventType", metricEventType)
				.param("traceId", traceId)
				.param("occurredAt", Timestamp.from(Instant.now()))
				.update();
	}
}
