package io.github.char0708.mch.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import io.github.char0708.mch.platform.shared.ApiException;

class PublicIdentityServiceUnitTests {

	private static final String ASSERTION = "R1_TEST_ASSERTION_32_BYTES_MINIMUM";

	@Test
	void syntheticFixtureIsFailClosedWhenNotExplicitlyEnabled() {
		PublicIdentityService service = new PublicIdentityService(null, null, null, false,
				"R1_SYNTHETIC_FIXTURE", ASSERTION);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("127.0.0.1");

		assertThatThrownBy(() -> service.establishSession(request, "R1_SYNTHETIC_FIXTURE",
				"r1-default-natural-person", ASSERTION, "request-id"))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.code()).isEqualTo("AUTHENTICATION_FAILED");
					assertThat(exception.status().value()).isEqualTo(401);
				});
	}

	@Test
	void enabledFixtureStillRejectsNonLoopbackRequestsBeforeIdentityLookup() {
		PublicIdentityService service = new PublicIdentityService(null, null, null, true,
				"R1_SYNTHETIC_FIXTURE", ASSERTION);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("198.51.100.10");

		assertThatThrownBy(() -> service.establishSession(request, "R1_SYNTHETIC_FIXTURE",
				"r1-default-natural-person", ASSERTION, "request-id"))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("AUTHENTICATION_FAILED"));
	}
}
