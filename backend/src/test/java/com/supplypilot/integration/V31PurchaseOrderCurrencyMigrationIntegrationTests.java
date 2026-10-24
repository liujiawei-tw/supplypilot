package com.supplypilot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Verifies V31 backfills historical purchase-order item currency snapshots on MySQL 8. */
@Testcontainers(disabledWithoutDocker = true)
class V31PurchaseOrderCurrencyMigrationIntegrationTests {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("supplypilot_po_currency_test")
            .withCommand("--log-bin-trust-function-creators=1");

    @Test
    void backfillsExistingItemsAndRequiresAllowedCurrency() {
        flyway("30").migrate();
        JdbcTemplate db = jdbc();
        seedLegacyPurchaseOrderItem(db);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = 'purchase_order_items' AND column_name = 'currency'",
                Integer.class)).isZero();

        flyway(null).migrate();

        assertThat(db.queryForObject(
                "SELECT currency FROM purchase_order_items WHERE id = 1001", String.class)).isEqualTo("TWD");
        assertThat(db.queryForObject("SELECT is_nullable FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = 'purchase_order_items' AND column_name = 'currency'",
                String.class)).isEqualTo("NO");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints " +
                "WHERE constraint_schema = DATABASE() AND table_name = 'purchase_order_items' " +
                "AND constraint_name = 'chk_purchase_order_items_currency' AND constraint_type = 'CHECK'",
                Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> db.update(
                "UPDATE purchase_order_items SET currency = 'USD' WHERE id = 1001"))
                .isInstanceOf(UncategorizedSQLException.class);
        assertThat(db.queryForObject(
                "SELECT currency FROM purchase_order_items WHERE id = 1001", String.class)).isEqualTo("TWD");
    }

    private static void seedLegacyPurchaseOrderItem(JdbcTemplate db) {
        db.update("INSERT INTO users (id, username, password_hash, display_name, role, status, " +
                "must_change_password, can_manage_users, created_at, updated_at) " +
                "VALUES (101, 'currency-migration-admin', 'hash', 'Migration Admin', 'ADMIN', 'ACTIVE', " +
                "FALSE, FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        db.update("INSERT INTO suppliers (id, code, name, reliability_score, quality_score, status, created_at, updated_at) " +
                "VALUES (401, 'CURRENCY-MIGRATION-SUP', 'Migration Supplier', 80, 80, 'ACTIVE', " +
                "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        db.update("INSERT INTO materials (id, code, name, base_unit, safety_stock, status, created_at, updated_at) " +
                "VALUES (601, 'CURRENCY-MIGRATION-MAT', 'Migration Material', 'KG', 0, 'ACTIVE', " +
                "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        db.update("INSERT INTO purchase_orders (id, po_number, supplier_id, status, stage, created_by, created_at, updated_at) " +
                "VALUES (901, 'PO-CURRENCY-MIGRATION-901', 401, 'DRAFT', 'NEW_REQUEST', 101, " +
                "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        db.update("INSERT INTO purchase_order_items (id, purchase_order_id, material_id, quantity, unit_price, unit, created_at) " +
                "VALUES (1001, 901, 601, 2, 25, 'KG', CURRENT_TIMESTAMP(6))");
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }
}
