package io.github.char0708.mch.platform.auth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.char0708.mch.platform.shared.ApiEnvelope;
import io.github.char0708.mch.platform.shared.RequestIds;

@RestController
@RequestMapping(path = "/api/v1/synthetic-auth", consumes = "application/json", produces = "application/json")
public class SyntheticAuthController {

	private final SyntheticAuthService authService;

	SyntheticAuthController(SyntheticAuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/sessions")
	ResponseEntity<ApiEnvelope<SyntheticAuthService.AuthResult>> login(
			@RequestBody SyntheticAuthService.LoginCommand command,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request) {
		String requestId = RequestIds.current(request);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new ApiEnvelope<>(requestId, authService.login(command, idempotencyKey, requestId)));
	}
}
