package io.github.char0708.mch.platform.content;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.char0708.mch.platform.identity.NaturalPersonActor;
import io.github.char0708.mch.platform.identity.PublicIdentityService;
import io.github.char0708.mch.platform.profile.ProfileService;
import io.github.char0708.mch.platform.shared.ApiEnvelope;
import io.github.char0708.mch.platform.shared.RequestIds;

@RestController
@RequestMapping(path = "/api/v1/public/contents", produces = "application/json")
public class PublicContentController {

	private final PublicIdentityService identityService;
	private final ProfileService profileService;
	private final ContentService contentService;

	PublicContentController(PublicIdentityService identityService, ProfileService profileService, ContentService contentService) {
		this.identityService = identityService;
		this.profileService = profileService;
		this.contentService = contentService;
	}

	@GetMapping("/{contentId}")
	ApiEnvelope<ContentService.PublishedContentView> get(@PathVariable String contentId, HttpServletRequest request) {
		NaturalPersonActor actor = identityService.authenticate(request);
		ProfileService.ProfileView profile = profileService.requireProfile(actor.personId());
		return new ApiEnvelope<>(RequestIds.current(request), contentService.getPublished(contentId, profile.stageCode()));
	}
}
