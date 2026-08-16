package io.github.char0708.mch.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ContentMetricsRemovalMigrationTests {

	@Container
	static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

	@Test
	void v0006DatabaseUpgradesForwardWithoutMetricsAndKeepsProtectedContentTables() throws Exception {
		Flyway.configure()
				.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
				.target("0006")
				.load()
				.migrate();

		assertThat(tableExists("content_metric_event")).isTrue();
		assertThat(tableExists("content_metric_aggregate")).isTrue();

		Flyway.configure()
				.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
				.load()
				.migrate();

		assertThat(tableExists("content_metric_event")).isFalse();
		assertThat(tableExists("content_metric_aggregate")).isFalse();
		assertThat(tableExists("r1_content_slot")).isTrue();
		assertThat(tableExists("content_item")).isTrue();
		assertThat(tableExists("content_version")).isTrue();
		assertThat(tableExists("synthetic_audit_event")).isTrue();
		assertThat(count("r1_content_slot")).isEqualTo(14);
	}

	private boolean tableExists(String tableName) throws Exception {
		try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("""
						SELECT COUNT(*)
						FROM information_schema.tables
						WHERE table_schema = DATABASE() AND table_name = '%s'
						""".formatted(tableName))) {
			resultSet.next();
			return resultSet.getInt(1) == 1;
		}
	}

	private int count(String tableName) throws Exception {
		try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
			resultSet.next();
			return resultSet.getInt(1);
		}
	}
}
