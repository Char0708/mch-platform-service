package io.github.char0708.mch.platform.profile;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.char0708.mch.platform.identity.NaturalPersonActor;
import io.github.char0708.mch.platform.identity.PublicIdentityService;
import io.github.char0708.mch.platform.shared.ApiEnvelope;
import io.github.char0708.mch.platform.shared.RequestIds;

@RestController
@RequestMapping(path = "/api/v1/me/health-subjects", produces = "application/json")
public class ProfileController {

	private final PublicIdentityService identityService;
	private final ProfileService profileService;

	ProfileController(PublicIdentityService identityService, ProfileService profileService) {
		this.identityService = identityService;
		this.profileService = profileService;
	}

	@PostMapping(path = "/self", consumes = "application/json")
	ResponseEntity<ApiEnvelope<ProfileService.ProfileView>> createSelfProfile(
			@RequestBody ProfileService.ProfileCommand command,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request) {
		NaturalPersonActor actor = identityService.authenticate(request);
		String requestId = RequestIds.current(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(new ApiEnvelope<>(requestId,
				profileService.createSelfProfile(actor, command, idempotencyKey, requestId)));
	}
}
