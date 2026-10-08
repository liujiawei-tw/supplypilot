package com.supplypilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.supplypilot.integration.AbstractIntegrationTest;

class SupplyPilotApplicationTests extends AbstractIntegrationTest {

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
