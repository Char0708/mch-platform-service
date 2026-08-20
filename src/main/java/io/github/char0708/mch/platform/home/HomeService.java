package io.github.char0708.mch.platform.home;

import java.util.List;

import org.springframework.stereotype.Service;

import io.github.char0708.mch.platform.content.ContentService;
import io.github.char0708.mch.platform.profile.ProfileService;
import io.github.char0708.mch.platform.weight.WeightService;

@Service
public class HomeService {

	private final ProfileService profileService;
	private final ContentService contentService;
	private final WeightService weightService;

	HomeService(ProfileService profileService, ContentService contentService, WeightService weightService) {
		this.profileService = profileService;
		this.contentService = contentService;
		this.weightService = weightService;
	}

	public HomeView load(String personId) {
		ProfileService.ProfileView profile = profileService.requireProfile(personId);
		List<ContentService.HomePublishedContentView> contents = contentService.findPublishedDailyChanges(
				profile.stageCode(), profile.gestationalAgeWeeks(), profile.gestationalAgeDays());
		WeightService.WeightTimeline weights = weightService.timeline(personId, profile.subjectId());
		return new HomeView(profile.subjectId(), profile.stageCode(), profile.stageBusinessTime(),
				contents, weights.latestWeight(), weights.trend());
	}

	public record HomeView(String subjectId, String stageCode, String stageBusinessTime,
			List<ContentService.HomePublishedContentView> contents, WeightService.WeightView latestWeight,
			List<WeightService.WeightView> trend) {
	}
}
