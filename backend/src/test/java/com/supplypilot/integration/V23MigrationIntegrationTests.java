package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Verifies an existing V23 database upgrades safely through the current PO stage migration on MySQL 8. */
@Testcontainers(disabledWithoutDocker = true)
class V23MigrationIntegrationTests {
    private static final int HISTORICAL_V23_CHECKSUM = 1761549949;

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("supplypilot_v23_test")
            .withCommand("--log-bin-trust-function-creators=1");

    @Test
    void validatesHistoricalV23AndMigratesExistingAccountsAuditRowsAndPoStageThroughCurrentVersion() {
        flyway(MYSQL.getJdbcUrl(), "22").migrate();
        JdbcTemplate db = jdbc(MYSQL.getJdbcUrl());
        seedV1Rows(db);

        flyway(MYSQL.getJdbcUrl(), "23").migrate();
        assertThat(db.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '23'", Integer.class))
                .isEqualTo(HISTORICAL_V23_CHECKSUM);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() AND table_name = 'users' AND index_name = 'idx_users_manager_status_id'", Integer.class))
                .isZero();

        // Reproduce the deployed mismatch in this disposable Testcontainers database.
        db.update("UPDATE flyway_schema_history SET checksum = 1985409924 WHERE version = '23'");
        var mismatch = flyway(MYSQL.getJdbcUrl(), null).validateWithResult();
        assertThat(mismatch.validationSuccessful).isFalse();
        assertThat(mismatch.invalidMigrations).anySatisfy(invalid ->
                assertThat(invalid.errorDetails.errorMessage).contains("checksum"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '24'", Integer.class))
                .isZero();

        // Restore only this disposable test row to its historically applied value,
        // proving normal Flyway validation and the additive upgrades then work.
        db.update("UPDATE flyway_schema_history SET checksum = ? WHERE version = '23'", HISTORICAL_V23_CHECKSUM);
        flyway(MYSQL.getJdbcUrl(), "27").migrate();
        db.update("UPDATE purchase_orders SET ordered_progress = 'FOLLOW_UP' WHERE id = 502");
        db.update("INSERT INTO purchase_orders (id, po_number, supplier_id, status, ordered_progress, created_by, created_at, updated_at) " +
                        "VALUES (504, 'PO-MIGRATION-504', 401, 'ORDERED', 'RECEIVING_INSPECTION', 101, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        db.update("INSERT INTO purchase_orders (id, po_number, supplier_id, status, payment_confirmed_by, " +
                        "payment_confirmed_by_user_id_snapshot, payment_confirmed_by_username_snapshot, " +
                        "payment_confirmed_by_display_name_snapshot, payment_confirmed_at, created_by, created_at, updated_at) " +
                        "VALUES (505, 'PO-MIGRATION-505', 401, 'RECEIVED', 101, 101, 'legacy_admin', 'Legacy Admin', " +
                        "CURRENT_TIMESTAMP(6), 101, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
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
        assertThat(db.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '23'", Integer.class))
                .isEqualTo(HISTORICAL_V23_CHECKSUM);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '24' AND success = TRUE", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() AND table_name = 'users' AND index_name = 'idx_users_manager_status_id'", Integer.class))
                .isGreaterThan(0);

        assertThat(db.queryForObject("SELECT created_by FROM purchase_orders WHERE id = 501", Long.class)).isEqualTo(102L);
        assertThat(db.queryForObject("SELECT stage FROM purchase_orders WHERE id = 501", String.class))
                .isEqualTo("NEW_REQUEST");
        assertThat(db.queryForObject("SELECT stage FROM purchase_orders WHERE id = 502", String.class))
                .isEqualTo("ORDER_FOLLOW_UP");
        assertThat(db.queryForObject("SELECT stage FROM purchase_orders WHERE id = 503", String.class))
                .isEqualTo("PAYMENT_RECONCILIATION");
        assertThat(db.queryForObject("SELECT stage FROM purchase_orders WHERE id = 506", String.class)).isNull();
        assertThat(db.queryForObject("SELECT ordered_progress FROM purchase_orders WHERE id = 506", String.class))
                .isEqualTo("LEGACY_UNKNOWN");
        assertThatThrownBy(() -> db.update(
                "UPDATE purchase_orders SET stage = 'RECEIVING_INSPECTION' WHERE id = 506"))
                .isInstanceOf(UncategorizedSQLException.class);
        assertThat(db.queryForObject("SELECT stage FROM purchase_orders WHERE id = 506", String.class)).isNull();
        assertThat(db.queryForObject("SELECT stage FROM purchase_orders WHERE id = 504", String.class))
                .isEqualTo("RECEIVING_INSPECTION");
        assertThat(db.queryForObject("SELECT stage FROM purchase_orders WHERE id = 505", String.class))
                .isEqualTo("COMPLETED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE created_by = 102", Long.class)).isEqualTo(4L);
        assertThat(db.queryForObject("SELECT assigned_to FROM purchase_orders WHERE id = 501", Long.class)).isEqualTo(102L);
        assertThat(db.queryForObject("SELECT created_by_user_id_snapshot FROM purchase_orders WHERE id = 501", Long.class)).isEqualTo(102L);
        assertThat(db.queryForObject("SELECT created_by_username_snapshot FROM purchase_orders WHERE id = 501", String.class)).isEqualTo("legacy_staff");
        assertThat(db.queryForObject("SELECT created_by_display_name_snapshot FROM purchase_orders WHERE id = 501", String.class)).isEqualTo("Legacy Staff");
        assertThat(db.queryForObject("SELECT created_by_user_id_snapshot FROM stock_transactions WHERE id = 701", Long.class)).isEqualTo(102L);
        assertThat(db.queryForObject("SELECT created_by_username_snapshot FROM stock_transactions WHERE id = 701", String.class)).isEqualTo("legacy_staff");
        assertThat(db.queryForObject("SELECT created_by_display_name_snapshot FROM stock_transactions WHERE id = 701", String.class)).isEqualTo("Legacy Staff");

        // V24 preserves historical PO and stock transaction identity after deletion.
        db.update("DELETE FROM users WHERE id = 102");
        assertThat(db.queryForObject("SELECT created_by FROM purchase_orders WHERE id = 501", Long.class)).isNull();
        assertThat(db.queryForObject("SELECT assigned_to FROM purchase_orders WHERE id = 501", Long.class)).isNull();
        assertThat(db.queryForObject("SELECT created_by_user_id_snapshot FROM purchase_orders WHERE id = 501", Long.class)).isEqualTo(102L);
        assertThat(db.queryForObject("SELECT created_by_display_name_snapshot FROM purchase_orders WHERE id = 501", String.class)).isEqualTo("Legacy Staff");
        assertThat(db.queryForObject("SELECT created_by FROM stock_transactions WHERE id = 701", Long.class)).isNull();
        assertThat(db.queryForObject("SELECT created_by_user_id_snapshot FROM stock_transactions WHERE id = 701", Long.class)).isEqualTo(102L);
        assertThat(db.queryForObject("SELECT created_by_display_name_snapshot FROM stock_transactions WHERE id = 701", String.class)).isEqualTo("Legacy Staff");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM stock_transactions WHERE id = 701", Integer.class)).isEqualTo(1);
    }

    private static void seedV1Rows(JdbcTemplate db) {
        db.update("INSERT INTO users (id, username, password_hash, display_name, role, status, must_change_password, created_at, updated_at) " +
                        "VALUES (101, 'legacy_admin', '$2a$10$legacy-admin-hash', 'Legacy Admin', 'ADMIN', 'ACTIVE', FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)), " +
                        "(102, 'legacy_staff', '$2a$10$legacy-staff-hash', 'Legacy Staff', 'PROCUREMENT_STAFF', 'ACTIVE', FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)), " +
                        "(103, 'legacy_manager', '$2a$10$legacy-manager-hash', 'Legacy Manager', 'PROCUREMENT_MANAGER', 'ACTIVE', FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)), " +
                        "(104, 'legacy_warehouse', '$2a$10$legacy-warehouse-hash', 'Legacy Warehouse', 'WAREHOUSE_STAFF', 'INACTIVE', FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        db.update("INSERT INTO suppliers (id, code, name, reliability_score, quality_score, status, created_at, updated_at) " +
                "VALUES (401, 'MIGRATION-SUP', 'Migration Supplier', 80, 80, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        db.update("INSERT INTO materials (id, code, name, base_unit, safety_stock, status, created_at, updated_at) " +
                "VALUES (601, 'MIGRATION-MATERIAL', 'Migration Material', 'KG', 0, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        db.update("INSERT INTO purchase_orders (id, po_number, supplier_id, status, created_by, created_at, updated_at) " +
                "VALUES (501, 'PO-MIGRATION-501', 401, 'DRAFT', 102, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)), " +
                "(502, 'PO-MIGRATION-502', 401, 'ORDERED', 102, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)), " +
                "(503, 'PO-MIGRATION-503', 401, 'RECEIVED', 102, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)), " +
                "(506, 'PO-MIGRATION-506', 401, 'ORDERED', 102, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        db.update("INSERT INTO stock_transactions (id, material_id, type, quantity, reason, created_by, created_at) " +
                "VALUES (701, 601, 'MANUAL_ADJUSTMENT', 1, 'legacy audit row', 102, CURRENT_TIMESTAMP(6))");
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
