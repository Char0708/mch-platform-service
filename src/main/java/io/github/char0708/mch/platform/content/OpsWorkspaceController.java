package io.github.char0708.mch.platform.content;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.char0708.mch.platform.auth.AccountRole;
import io.github.char0708.mch.platform.auth.SyntheticAuthService;
import io.github.char0708.mch.platform.shared.ApiEnvelope;
import io.github.char0708.mch.platform.shared.RequestIds;

@RestController
@RequestMapping(path = "/api/v1/ops", produces = "application/json")
public class OpsWorkspaceController {

	private static final AccountRole[] ALL_OPS_ROLES = { AccountRole.CONTENT_EDITOR, AccountRole.CONTENT_REVIEWER,
			AccountRole.PLATFORM_ADMIN };
	private final SyntheticAuthService authService;
	private final ContentService contentService;

	OpsWorkspaceController(SyntheticAuthService authService, ContentService contentService) {
		this.authService = authService;
		this.contentService = contentService;
	}

	@GetMapping("/content-slots")
	ApiEnvelope<ContentService.ContentSlotListView> slots(
			@RequestParam(required = false) Integer dayOffset,
			@RequestParam(required = false) String contentTypeCode,
			@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "false") boolean missingOnly,
			HttpServletRequest request) {
		authService.authenticate(request, ALL_OPS_ROLES);
		return new ApiEnvelope<>(RequestIds.current(request),
				contentService.contentSlots(dayOffset, contentTypeCode, status, missingOnly));
	}

	@GetMapping("/workbench")
	ApiEnvelope<ContentService.WorkbenchView> workbench(HttpServletRequest request) {
		authService.authenticate(request, ALL_OPS_ROLES);
		return new ApiEnvelope<>(RequestIds.current(request), contentService.workbench());
	}

	@GetMapping("/review-queue")
	ApiEnvelope<ContentService.ReviewQueueView> reviewQueue(HttpServletRequest request) {
		authService.authenticate(request, AccountRole.CONTENT_REVIEWER, AccountRole.PLATFORM_ADMIN);
		return new ApiEnvelope<>(RequestIds.current(request), contentService.reviewQueue());
	}
}
