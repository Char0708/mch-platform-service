package io.github.char0708.mch.platform.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class R1OpsFixtureConfiguration {

	@Bean
	ApplicationRunner r1OpsFixture(JdbcClient jdbcClient, PasswordEncoder passwordEncoder,
			@Value("${mch.ops.synthetic-fixture.enabled:false}") boolean enabled,
			@Value("${mch.ops.synthetic-fixture.password:}") String password) {
		return arguments -> {
			if (!enabled) {
				return;
			}
			if (password.length() < 16) {
				throw new IllegalStateException("R1 OPS fixture password must contain at least 16 characters.");
			}
			Instant now = Instant.now();
			for (FixtureAccount account : List.of(
					new FixtureAccount("10000000000000000000000000000001", "r1-editor", AccountRole.CONTENT_EDITOR),
					new FixtureAccount("10000000000000000000000000000002", "r1-reviewer", AccountRole.CONTENT_REVIEWER),
					new FixtureAccount("10000000000000000000000000000003", "r1-admin", AccountRole.PLATFORM_ADMIN))) {
				jdbcClient.sql("""
						INSERT INTO synthetic_account
						(account_id, username_normalized, password_hash, account_type, status, profile_status, synthetic, created_at)
						VALUES (:accountId, :username, :passwordHash, :accountType, 'ACTIVE', 'READY', TRUE, :createdAt)
						ON DUPLICATE KEY UPDATE password_hash = :passwordHash, account_type = :accountType,
						 status = 'ACTIVE', profile_status = 'READY', synthetic = TRUE
						""")
						.param("accountId", account.accountId())
						.param("username", account.username())
						.param("passwordHash", passwordEncoder.encode(password))
						.param("accountType", account.role().name())
						.param("createdAt", Timestamp.from(now))
						.update();
			}
		};
	}

	private record FixtureAccount(String accountId, String username, AccountRole role) {
	}
}
