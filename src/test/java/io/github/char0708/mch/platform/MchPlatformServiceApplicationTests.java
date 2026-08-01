package io.github.char0708.mch.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.core.ApplicationModules;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MchPlatformServiceApplicationTests {

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void contextLoads() {
	}

	@Test
	void engineeringSchemaProbeIsMigrated() {
		Integer rowCount = jdbcClient.sql("SELECT COUNT(*) FROM engineering_schema_probe")
				.query(Integer.class)
				.single();

		org.assertj.core.api.Assertions.assertThat(rowCount).isZero();
	}

	@Test
	void moduleStructureIsValid() {
		ApplicationModules.of(MchPlatformServiceApplication.class).verify();
	}

}
