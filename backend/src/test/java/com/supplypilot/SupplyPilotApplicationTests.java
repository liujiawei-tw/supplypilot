package com.supplypilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class SupplyPilotApplicationTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoadsWithMySqlAndFlywayBaseline() {
		String databaseProduct = jdbcTemplate.queryForObject(
				"SELECT @@version_comment",
				String.class
		);
		Integer appliedBaselineCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
				Integer.class
		);

		assertTrue(databaseProduct != null && databaseProduct.contains("MySQL"));
		assertEquals(1, appliedBaselineCount);
	}

}
