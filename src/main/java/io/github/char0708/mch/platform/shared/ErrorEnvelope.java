package io.github.char0708.mch.platform.shared;

import java.util.List;

public record ErrorEnvelope(ErrorBody error) {

	public record ErrorBody(String code, String message, List<ApiException.FieldError> fieldErrors, String requestId) {
	}
}
