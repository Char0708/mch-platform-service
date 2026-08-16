package io.github.char0708.mch.platform.home;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.char0708.mch.platform.identity.NaturalPersonActor;
import io.github.char0708.mch.platform.identity.PublicIdentityService;
import io.github.char0708.mch.platform.shared.ApiEnvelope;
import io.github.char0708.mch.platform.shared.RequestIds;

@RestController
@RequestMapping(path = "/api/v1/me/home", produces = "application/json")
public class HomeController {

	private final PublicIdentityService identityService;
	private final HomeService homeService;

	HomeController(PublicIdentityService identityService, HomeService homeService) {
		this.identityService = identityService;
		this.homeService = homeService;
	}

	@GetMapping
	ApiEnvelope<HomeService.HomeView> home(HttpServletRequest request) {
		NaturalPersonActor actor = identityService.authenticate(request);
		return new ApiEnvelope<>(RequestIds.current(request), homeService.load(actor.personId()));
	}
}
