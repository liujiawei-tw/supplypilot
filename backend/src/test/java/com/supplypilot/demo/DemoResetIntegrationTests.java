package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.UUID;

import com.supplypilot.TestcontainersConfiguration;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.semantic.SemanticStore;
import com.supplypilot.testsupport.FixedClockConfiguration;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "supplypilot.demo.account-password=Demo-Password-For-Tests-901!",
        "supplypilot.demo.allowed-database=supplypilot_demo_test",
        "supplypilot.demo.samples-path=../samples/documents",
        "supplypilot.jwt.secret=dGVzdC1zZWNyZXQtZm9yLWRlbW8tcmVzZXQtdGVzdC1hdC1sZWFzdC0yNTYtYml0cw==",
        "supplypilot.frontend-origin=http://localhost:5173"
})
@ActiveProfiles("demo")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class DemoResetIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("supplypilot_semantic_demo_test");
    private static final Path DOCUMENT_ROOT;

    static {
        POSTGRES.start();
        try {
            DOCUMENT_ROOT = Files.createTempDirectory("supplypilot-demo-reset-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("supplypilot.semantic.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("supplypilot.semantic.username", POSTGRES::getUsername);
        registry.add("supplypilot.semantic.password", POSTGRES::getPassword);
        registry.add("supplypilot.document-storage.path", () -> DOCUMENT_ROOT.toString());
    }

    @Autowired private DemoDatasetCoordinator coordinator;
    @Autowired private DemoProperties properties;
    @Autowired private SemanticStore semanticStore;
    @Autowired private SemanticProperties semanticProperties;
    @Autowired private UserRepository users;
    @Autowired private DocumentMetadataRepository documents;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void guardedResetClearsBothDatabasesOriginalFilesAndSyncConflictsBeforeRebuildingBaseline() throws Exception {
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        semanticStore.audit(adminId, 1, 0, 0, 0, 0);
        assertThat(postgresCount("semantic_gate_audit")).isEqualTo(1);
        assertThat(fileCount()).isEqualTo(115);

        properties.setResetConfirmation("wrong-confirmation");
        assertThatThrownBy(coordinator::resetAndInitialize).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmation");
        assertThat(users.count()).isEqualTo(1);

        properties.setResetConfirmation(DemoDatasetCoordinator.RESET_CONFIRMATION);
        properties.setAllowedDatabase("other_demo_database");
        assertThatThrownBy(coordinator::resetAndInitialize).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected MySQL database");
        assertThat(users.count()).isEqualTo(1);

        properties.setAllowedDatabase("supplypilot_demo_test");
        properties.setAllowedSemanticDatabase("shared_semantic");
        assertThatThrownBy(coordinator::resetAndInitialize).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("semantic database is not explicitly demo-isolated");
        assertThat(postgresCount("semantic_gate_audit")).isEqualTo(1);

        assertThatThrownBy(() -> semanticStore.resetAllDemoData("other_demo_database"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected PostgreSQL database");
        assertThat(postgresCount("semantic_gate_audit")).isEqualTo(1);

        properties.setAllowedSemanticDatabase("supplypilot_semantic_demo_test");
        seedManualUploadVector();
        assertThat(postgresCount("semantic_rag_vectors")).isEqualTo(1);
        Long documentId = jdbc.queryForObject("SELECT id FROM document_metadata LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO demo_corpus_sync_conflicts
                    (conflict_key, dataset_key, corpus_version, document_key, document_id,
                     conflict_code, details, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))
                """, "a".repeat(64), "SP-901", "test-corpus", "test-document", documentId,
                "TEST_CONFLICT", "reset regression fixture");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_corpus_sync_conflicts", Long.class)).isEqualTo(1);
        try {
            coordinator.resetAndInitialize();
        } finally {
            properties.setResetConfirmation(null);
        }

        assertThat(postgresCount("semantic_gate_audit")).isZero();
        assertThat(postgresCount("semantic_rag_vectors")).isZero();
        assertThat(postgresCount("semantic_document_indexes")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_corpus_sync_conflicts", Long.class)).isZero();
        assertThat(users.count()).isEqualTo(1);
        assertThat(documents.count()).isEqualTo(115);
        assertThat(fileCount()).isEqualTo(115);

        long materialId = jdbc.queryForObject(
                "SELECT id FROM materials WHERE code = 'MAT-001'", Long.class);
        jdbc.update("UPDATE inventory SET quantity = 999 WHERE material_id = ?", materialId);
        coordinator.initialize();
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE material_id = ?", java.math.BigDecimal.class, materialId))
                .isEqualByComparingTo("999");
    }

    private long postgresCount(String table) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void seedManualUploadVector() throws Exception {
        semanticStore.ensureSchema();
        String vector = "[" + String.join(",", Collections.nCopies(semanticProperties.dimensions(), "0")) + "]";
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     INSERT INTO semantic_rag_vectors (id, content, metadata, embedding)
                     VALUES (?, ?, CAST(? AS json), CAST(? AS vector))
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "manual UI upload without demo dataset metadata");
            statement.setString(3, """
                    {"documentId":"999999","documentVersion":"manual-v1","generationKey":"manual-generation",
                     "sourceHash":"manual-hash","chunkIndex":0,"startOffset":0,"endOffset":49}
                    """);
            statement.setString(4, vector);
            statement.executeUpdate();
        }
    }

    private long fileCount() throws Exception {
        try (var paths = Files.list(DOCUMENT_ROOT)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }
}
