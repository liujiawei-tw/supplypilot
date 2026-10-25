package com.supplypilot.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class V33TrustedDemoMigrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("supplypilot_v33_migration_test")
            .withCommand("--log-bin-trust-function-creators=1");

    @Test
    void v33BackfillsExistingV32HumanApprovalBeforeAddingReviewCheck() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("32"))
                .load()
                .migrate();

        long userId;
        long documentId;
        Instant reviewedAt = Instant.parse("2026-01-02T03:04:05Z");
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (PreparedStatement insertUser = connection.prepareStatement("""
                    INSERT INTO users (username, password_hash, display_name, role, status, created_at, updated_at)
                    VALUES ('v33-legacy-review', 'hash', 'Legacy Reviewer', 'ADMIN', 'ACTIVE', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insertUser.setTimestamp(1, Timestamp.from(reviewedAt));
                insertUser.setTimestamp(2, Timestamp.from(reviewedAt));
                insertUser.executeUpdate();
                try (ResultSet keys = insertUser.getGeneratedKeys()) {
                    keys.next();
                    userId = keys.getLong(1);
                }
            }
            try (PreparedStatement insertDocument = connection.prepareStatement("""
                    INSERT INTO document_metadata
                        (document_type, title, version, storage_path, status, uploaded_by, uploaded_at, updated_at,
                         semantic_approved_source_hash, semantic_approved_by, semantic_approved_at,
                         semantic_review_version, semantic_review_status)
                    VALUES ('SUPPLIER_CONTRACT', 'Legacy approved document', 'v1', 'legacy-v32.txt', 'READY', ?, ?, ?, ?, ?, ?, 'v1', 'APPROVED')
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insertDocument.setLong(1, userId);
                insertDocument.setTimestamp(2, Timestamp.from(reviewedAt));
                insertDocument.setTimestamp(3, Timestamp.from(reviewedAt));
                insertDocument.setString(4, "a".repeat(64));
                insertDocument.setLong(5, userId);
                insertDocument.setTimestamp(6, Timestamp.from(reviewedAt));
                insertDocument.executeUpdate();
                try (ResultSet keys = insertDocument.getGeneratedKeys()) {
                    keys.next();
                    documentId = keys.getLong(1);
                }
            }
        }

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement select = connection.prepareStatement("""
                     SELECT semantic_review_actor_type, semantic_review_actor, semantic_review_trigger,
                            semantic_review_policy_decision, semantic_review_version
                     FROM document_metadata WHERE id = ?
                     """)) {
            select.setLong(1, documentId);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("semantic_review_actor_type")).isEqualTo("USER");
                assertThat(row.getString("semantic_review_actor")).isEqualTo(Long.toString(userId));
                assertThat(row.getString("semantic_review_trigger")).isEqualTo("ADMIN_OUTBOUND_REVIEW");
                assertThat(row.getString("semantic_review_policy_decision")).isEqualTo("APPROVED");
                assertThat(row.getString("semantic_review_version")).isEqualTo("v1");
            }
        }
        assertPartialNullableTuplesRejected();
    }

    private void assertPartialNullableTuplesRejected() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO document_metadata
                        (document_type, title, version, storage_path, status, uploaded_at, updated_at, demo_dataset_key)
                    VALUES ('SUPPLIER_CONTRACT', 'Partial provenance', 'v1', 'partial-provenance.txt', 'PROCESSING',
                            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 'SP-901')
                    """))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("chk_document_demo_provenance");

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO document_metadata
                        (document_type, title, version, storage_path, status, uploaded_at, updated_at,
                         semantic_review_actor_type)
                    VALUES ('SUPPLIER_CONTRACT', 'Partial review', 'v1', 'partial-review.txt', 'PROCESSING',
                            CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 'SYSTEM')
                    """))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("chk_document_semantic_review_actor_type");
        }
    }
}