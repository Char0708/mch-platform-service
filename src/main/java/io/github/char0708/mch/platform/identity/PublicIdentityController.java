package io.github.char0708.mch.platform.identity;

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
@RequestMapping(path = "/api/v1/public-identity", consumes = "application/json", produces = "application/json")
public class PublicIdentityController {

	private final PublicIdentityService identityService;

	PublicIdentityController(PublicIdentityService identityService) {
		this.identityService = identityService;
	}

	@PostMapping("/sessions")
	ResponseEntity<ApiEnvelope<PublicIdentityService.IdentitySessionResult>> establishSession(
			@RequestBody IdentitySessionCommand command,
			@RequestHeader(value = "X-Trusted-Identity-Provider", required = false) String identityProvider,
			@RequestHeader(value = "X-Trusted-Person-Reference", required = false) String personReference,
			@RequestHeader(value = "X-Trusted-Identity-Assertion", required = false) String identityAssertion,
			HttpServletRequest request) {
		String requestId = RequestIds.current(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(new ApiEnvelope<>(requestId,
				identityService.establishSession(request, identityProvider, personReference, identityAssertion, requestId)));
	}

	public record IdentitySessionCommand() {
	}
}
