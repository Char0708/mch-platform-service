package io.github.char0708.mch.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.core.ApplicationModules;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
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
		Map<String, Object> seededPerson = jdbcClient.sql("""
				SELECT display_name, date_of_birth, profile_status
				FROM natural_person
				WHERE identity_provider = 'R1_SYNTHETIC_FIXTURE'
				  AND external_person_reference = 'r1-default-natural-person'
				""").query().singleRow();

		assertThat(rowCount).isZero();
		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM natural_person").query(Integer.class).single()).isEqualTo(1);
		assertThat(seededPerson.get("display_name")).isEqualTo("林女士");
		assertThat(seededPerson.get("date_of_birth")).isEqualTo(java.sql.Date.valueOf(LocalDate.of(1994, 6, 15)));
		assertThat(seededPerson.get("profile_status")).isEqualTo("REQUIRED");
		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM health_subject").query(Integer.class).single()).isZero();
		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM explicit_stage_profile").query(Integer.class).single()).isZero();
		assertThat(jdbcClient.sql("SELECT COUNT(*) FROM weight_management_baseline").query(Integer.class).single()).isZero();
	}

	@Test
	void moduleStructureIsValid() {
		ApplicationModules.of(MchPlatformServiceApplication.class).verify();
	}

}
