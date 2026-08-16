package io.github.char0708.mch.platform.weight;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.char0708.mch.platform.identity.NaturalPersonActor;
import io.github.char0708.mch.platform.identity.PublicIdentityService;
import io.github.char0708.mch.platform.shared.ApiEnvelope;
import io.github.char0708.mch.platform.shared.RequestIds;

@RestController
@RequestMapping(path = "/api/v1/me/health-subjects/{subjectId}/weights", produces = "application/json")
public class WeightController {

	private final PublicIdentityService identityService;
	private final WeightService weightService;

	WeightController(PublicIdentityService identityService, WeightService weightService) {
		this.identityService = identityService;
		this.weightService = weightService;
	}

	@PostMapping(consumes = "application/json")
	ResponseEntity<ApiEnvelope<WeightService.WeightView>> record(@PathVariable String subjectId,
			@RequestBody WeightService.WeightCommand command,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request) {
		NaturalPersonActor actor = identityService.authenticate(request);
		String requestId = RequestIds.current(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(new ApiEnvelope<>(requestId,
				weightService.record(actor, subjectId, command, idempotencyKey, requestId)));
	}

	@GetMapping
	ApiEnvelope<WeightService.WeightTimeline> list(@PathVariable String subjectId, HttpServletRequest request) {
		NaturalPersonActor actor = identityService.authenticate(request);
		return new ApiEnvelope<>(RequestIds.current(request), weightService.timeline(actor.personId(), subjectId));
	}

	@PutMapping(path = "/baseline", consumes = "application/json")
	ApiEnvelope<WeightService.WeightManagementBaseline> saveBaseline(@PathVariable String subjectId,
			@RequestBody WeightService.WeightManagementBaselineCommand command,
			HttpServletRequest request) {
		NaturalPersonActor actor = identityService.authenticate(request);
		String requestId = RequestIds.current(request);
		return new ApiEnvelope<>(requestId, weightService.saveBaseline(actor, subjectId, command, requestId));
	}
}
