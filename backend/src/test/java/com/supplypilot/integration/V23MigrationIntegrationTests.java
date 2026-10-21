package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs the actual V22-to-V23 migration against an isolated MySQL 8 schema. */
@Testcontainers(disabledWithoutDocker = true)
class V23MigrationIntegrationTests {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("supplypilot_v23_test")
            .withCommand("--log-bin-trust-function-creators=1");

    @Test
    void migratesExistingAccountsWithoutChangingIdentityCredentialsStatusOrBusinessReferences() throws Exception {
        Flyway flyway = flyway(MYSQL.getJdbcUrl(), "22");
        flyway.migrate();
        JdbcTemplate db = jdbc(MYSQL.getJdbcUrl());
        seedV1Rows(db);

        flyway(MYSQL.getJdbcUrl(), null).migrate();

            List<Map<String, Object>> migrated = db.queryForList(
                    "SELECT id, username, password_hash, role, status, can_manage_users FROM users ORDER BY id");
            assertThat(migrated).hasSize(4);
            assertThat(migrated).allSatisfy(row -> assertThat(row.get("role")).isEqualTo("ADMIN"));
            assertThat(migrated.get(0)).containsEntry("id", 101L)
                    .containsEntry("username", "legacy_admin")
                    .containsEntry("password_hash", "$2a$10$legacy-admin-hash")
                    .containsEntry("status", "ACTIVE")
                    .containsEntry("can_manage_users", true);
            assertThat(migrated.get(1)).containsEntry("id", 102L)
                    .containsEntry("password_hash", "$2a$10$legacy-staff-hash")
                    .containsEntry("can_manage_users", false);
            assertThat(migrated.get(2)).containsEntry("role", "ADMIN").containsEntry("can_manage_users", false);
            assertThat(migrated.get(3)).containsEntry("status", "INACTIVE").containsEntry("can_manage_users", false);
            assertThat(db.queryForObject("SELECT created_by FROM purchase_orders WHERE id = 501", Long.class)).isEqualTo(102L);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE created_by = 102", Long.class)).isEqualTo(1L);
    }

    private static void seedV1Rows(JdbcTemplate db) {
        db.update("INSERT INTO users (id, username, password_hash, display_name, role, status, must_change_password, created_at, updated_at) " +
                        "VALUES (101, 'legacy_admin', '$2a$10$legacy-admin-hash', 'Legacy Admin', 'ADMIN', 'ACTIVE', FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)), " +
                        "(102, 'legacy_staff', '$2a$10$legacy-staff-hash', 'Legacy Staff', 'PROCUREMENT_STAFF', 'ACTIVE', FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)), " +
                        "(103, 'legacy_manager', '$2a$10$legacy-manager-hash', 'Legacy Manager', 'PROCUREMENT_MANAGER', 'ACTIVE', FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)), " +
                        "(104, 'legacy_warehouse', '$2a$10$legacy-warehouse-hash', 'Legacy Warehouse', 'WAREHOUSE_STAFF', 'INACTIVE', FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        db.update("INSERT INTO suppliers (id, code, name, reliability_score, quality_score, status, created_at, updated_at) " +
                "VALUES (401, 'MIGRATION-SUP', 'Migration Supplier', 80, 80, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        db.update("INSERT INTO purchase_orders (id, po_number, supplier_id, status, created_by, created_at, updated_at) " +
                "VALUES (501, 'PO-MIGRATION-501', 401, 'DRAFT', 102, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
    }

    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure()
                .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static JdbcTemplate jdbc(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
        return new JdbcTemplate(source);
    }

}
