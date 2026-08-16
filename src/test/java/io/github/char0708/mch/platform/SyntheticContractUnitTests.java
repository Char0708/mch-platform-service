package io.github.char0708.mch.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import io.github.char0708.mch.platform.shared.ApiException;
import io.github.char0708.mch.platform.shared.BusinessTimes;
import io.github.char0708.mch.platform.shared.Hashing;

class SyntheticContractUnitTests {

	@Test
	void businessTimeRequiresAnExplicitOffsetAndNormalizesToAnInstant() {
		Instant receivedAt = Instant.parse("2026-08-09T12:00:00Z");
		assertThat(BusinessTimes.parseNotFuture("2026-08-09T19:30:00+08:00", "businessTime", receivedAt))
				.isEqualTo(Instant.parse("2026-08-09T11:30:00Z"));
		assertThatThrownBy(() -> BusinessTimes.parseNotFuture("2026-08-09T11:30:00", "businessTime", receivedAt))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.code()).isEqualTo("INVALID_REQUEST"));
	}

	@Test
	void futureBusinessTimeFailsWithoutBeingRewritten() {
		Instant receivedAt = Instant.parse("2026-08-09T12:00:00Z");
		assertThatThrownBy(() -> BusinessTimes.parseNotFuture("2026-08-09T12:00:01Z", "businessTime", receivedAt))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("BUSINESS_TIME_IN_FUTURE"));
	}

	@Test
	void canonicalHashingIsStableAndPayloadSensitive() {
		assertThat(Hashing.sha256("synthetic|payload")).isEqualTo(Hashing.sha256("synthetic|payload"))
				.isNotEqualTo(Hashing.sha256("synthetic|different"))
				.hasSize(64);
	}

	@Test
	void bcryptUsesSaltedAdaptiveOneWayHashes() {
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
		String raw = "SyntheticUnit!A1";
		String first = encoder.encode(raw);
		String second = encoder.encode(raw);
		assertThat(first).startsWith("$2").isNotEqualTo(second).doesNotContain(raw);
		assertThat(encoder.matches(raw, first)).isTrue();
		assertThat(encoder.matches("SyntheticWrong!A1", first)).isFalse();
	}
}
