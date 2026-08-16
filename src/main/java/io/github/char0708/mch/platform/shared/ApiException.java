package io.github.char0708.mch.platform.shared;

import java.util.List;

import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final List<FieldError> fieldErrors;

	public ApiException(HttpStatus status, String code, String message) {
		this(status, code, message, List.of());
	}

	public ApiException(HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
		super(message);
		this.status = status;
		this.code = code;
		this.fieldErrors = List.copyOf(fieldErrors);
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public List<FieldError> fieldErrors() {
		return fieldErrors;
	}

	public record FieldError(String field, String reason) {
	}
}
