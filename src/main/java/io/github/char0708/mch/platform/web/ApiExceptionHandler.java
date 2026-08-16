package io.github.char0708.mch.platform.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.github.char0708.mch.platform.audit.AuditService;
import io.github.char0708.mch.platform.auth.SessionActor;
import io.github.char0708.mch.platform.identity.NaturalPersonActor;
import io.github.char0708.mch.platform.shared.ApiException;
import io.github.char0708.mch.platform.shared.ErrorEnvelope;
import io.github.char0708.mch.platform.shared.RequestIds;

@RestControllerAdvice
final class ApiExceptionHandler {

	private final AuditService auditService;

	ApiExceptionHandler(AuditService auditService) {
		this.auditService = auditService;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorEnvelope> handleApiException(ApiException exception, HttpServletRequest request) {
		return response(exception.status(), exception.code(), exception.getMessage(), exception.fieldErrors(), request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
		List<ApiException.FieldError> errors = exception.getBindingResult().getFieldErrors().stream()
				.map(error -> new ApiException.FieldError(error.getField(), "INVALID_VALUE"))
				.toList();
		return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求字段无效。", errors, request);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MissingRequestHeaderException.class })
	ResponseEntity<ErrorEnvelope> handleMalformedRequest(Exception exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求格式或必填字段无效。", List.of(), request);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ErrorEnvelope> handleNoResource(NoResourceFoundException exception, HttpServletRequest request) {
		return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "请求的接口不存在。", List.of(), request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorEnvelope> handleUnexpected(Exception exception, HttpServletRequest request) {
		return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时无法完成该合成请求。", List.of(), request);
	}

	private ResponseEntity<ErrorEnvelope> response(HttpStatus status, String code, String message,
			List<ApiException.FieldError> fieldErrors, HttpServletRequest request) {
		String requestId = RequestIds.current(request);
		SessionActor accountActor = request.getAttribute(SessionActor.REQUEST_ATTRIBUTE) instanceof SessionActor value ? value : null;
		NaturalPersonActor personActor = request.getAttribute(NaturalPersonActor.REQUEST_ATTRIBUTE) instanceof NaturalPersonActor value ? value : null;
		String actorId = personActor != null ? personActor.personId() : accountActor == null ? null : accountActor.accountId();
		String actorRole = personActor != null ? NaturalPersonActor.PRINCIPAL_TYPE : accountActor == null ? null : accountActor.role().name();
		try {
			auditService.failure(actorId, actorRole,
					request.getMethod() + " " + request.getRequestURI(), null, null, code, requestId, false);
		}
		catch (RuntimeException ignored) {
			// The stable error envelope must not be replaced by a secondary audit-write failure.
		}
		ErrorEnvelope envelope = new ErrorEnvelope(new ErrorEnvelope.ErrorBody(code, message, fieldErrors, requestId));
		return ResponseEntity.status(status).body(envelope);
	}
}
