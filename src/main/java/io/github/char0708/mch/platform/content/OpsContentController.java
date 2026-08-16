package io.github.char0708.mch.platform.content;

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

import io.github.char0708.mch.platform.auth.AccountRole;
import io.github.char0708.mch.platform.auth.SessionActor;
import io.github.char0708.mch.platform.auth.SyntheticAuthService;
import io.github.char0708.mch.platform.shared.ApiEnvelope;
import io.github.char0708.mch.platform.shared.RequestIds;

@RestController
@RequestMapping(path = "/api/v1/ops/contents", produces = "application/json")
public class OpsContentController {

	private final SyntheticAuthService authService;
	private final ContentService contentService;

	OpsContentController(SyntheticAuthService authService, ContentService contentService) {
		this.authService = authService;
		this.contentService = contentService;
	}

	@PostMapping(consumes = "application/json")
	ResponseEntity<ApiEnvelope<ContentService.ContentVersionView>> create(
			@RequestBody ContentService.CreateContentCommand command,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request) {
		SessionActor actor = authService.authenticate(request, AccountRole.CONTENT_EDITOR, AccountRole.PLATFORM_ADMIN);
		String requestId = RequestIds.current(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(new ApiEnvelope<>(requestId,
				contentService.create(actor, command, idempotencyKey, requestId)));
	}

	@PostMapping(path = "/{contentId}/versions", consumes = "application/json")
	ResponseEntity<ApiEnvelope<ContentService.ContentVersionView>> createSuccessorDraft(
			@PathVariable String contentId, @RequestBody ContentService.CreateSuccessorDraftCommand command,
			HttpServletRequest request) {
		SessionActor actor = authService.authenticate(request, AccountRole.CONTENT_EDITOR, AccountRole.PLATFORM_ADMIN);
		String requestId = RequestIds.current(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(new ApiEnvelope<>(requestId,
				contentService.createSuccessorDraft(actor, contentId, command, requestId)));
	}

	@PutMapping(path = "/{contentId}/versions/{versionId}", consumes = "application/json")
	ApiEnvelope<ContentService.ContentVersionView> update(@PathVariable String contentId,
			@PathVariable String versionId, @RequestBody ContentService.UpdateContentCommand command,
			@RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request) {
		SessionActor actor = authService.authenticate(request, AccountRole.CONTENT_EDITOR, AccountRole.PLATFORM_ADMIN);
		String requestId = RequestIds.current(request);
		return new ApiEnvelope<>(requestId,
				contentService.update(actor, contentId, versionId, command, ifMatch, idempotencyKey, requestId));
	}

	@PostMapping(path = "/{contentId}/versions/{versionId}/submit", consumes = "application/json")
	ApiEnvelope<ContentService.ContentVersionView> submit(@PathVariable String contentId,
			@PathVariable String versionId, @RequestBody ContentService.EmptyCommand command,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request) {
		SessionActor actor = authService.authenticate(request, AccountRole.CONTENT_EDITOR, AccountRole.PLATFORM_ADMIN);
		String requestId = RequestIds.current(request);
		return new ApiEnvelope<>(requestId, contentService.submit(actor, contentId, versionId, idempotencyKey, requestId));
	}

	@PostMapping(path = "/{contentId}/versions/{versionId}/review-decisions", consumes = "application/json")
	ApiEnvelope<ContentService.ContentVersionView> review(@PathVariable String contentId,
			@PathVariable String versionId, @RequestBody ContentService.ReviewCommand command,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request) {
		SessionActor actor = authService.authenticate(request, AccountRole.CONTENT_REVIEWER, AccountRole.PLATFORM_ADMIN);
		String requestId = RequestIds.current(request);
		return new ApiEnvelope<>(requestId, contentService.review(actor, contentId, versionId, command, idempotencyKey, requestId));
	}

	@PostMapping(path = "/{contentId}/versions/{versionId}/publish", consumes = "application/json")
	ApiEnvelope<ContentService.ContentVersionView> publish(@PathVariable String contentId,
			@PathVariable String versionId, @RequestBody ContentService.EmptyCommand command,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request) {
		SessionActor actor = authService.authenticate(request, AccountRole.CONTENT_REVIEWER, AccountRole.PLATFORM_ADMIN);
		String requestId = RequestIds.current(request);
		return new ApiEnvelope<>(requestId, contentService.publish(actor, contentId, versionId, idempotencyKey, requestId));
	}

	@GetMapping("/{contentId}")
	ApiEnvelope<ContentService.OpsContentView> get(@PathVariable String contentId, HttpServletRequest request) {
		authService.authenticate(request, AccountRole.CONTENT_EDITOR, AccountRole.CONTENT_REVIEWER,
				AccountRole.PLATFORM_ADMIN);
		return new ApiEnvelope<>(RequestIds.current(request), contentService.getOpsContent(contentId));
	}
}
