package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import com.supplypilot.TestcontainersConfiguration;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.document.semantic.SemanticStore;
import com.supplypilot.testsupport.FixedClockConfiguration;
import com.supplypilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "supplypilot.demo.account-password=Demo-Password-For-Tests-901!",
        "supplypilot.demo.samples-path=../samples/documents",
        "supplypilot.jwt.secret=dGVzdC1zZWNyZXQtZm9yLWRlbW8tcmVzZXQtdGVzdC1hdC1sZWFzdC0yNTYtYml0cw==",
        "supplypilot.frontend-origin=http://localhost:5173"
})
@ActiveProfiles("demo")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class DemoResetIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
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
    @Autowired private UserRepository users;
    @Autowired private DocumentMetadataRepository documents;

    @Test
    void guardedResetClearsBothDatabasesAndOriginalFilesBeforeRebuildingBaseline() throws Exception {
        long adminId = users.findByUsername("admin").orElseThrow().getId();
        semanticStore.audit(adminId, 1, 0, 0, 0, 0);
        assertThat(postgresCount("semantic_gate_audit")).isEqualTo(1);
        assertThat(fileCount()).isEqualTo(5);

        properties.setResetEnabled(true);
        properties.setResetConfirmation("wrong-confirmation");
        assertThatThrownBy(coordinator::initialize).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmation");
        assertThat(users.count()).isEqualTo(5);

        properties.setResetConfirmation(DemoDatasetCoordinator.RESET_CONFIRMATION);
        properties.setAllowedDatabase("not-the-active-database");
        assertThatThrownBy(coordinator::initialize).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected MySQL database");
        assertThat(users.count()).isEqualTo(5);

        properties.setAllowedDatabase("test");
        properties.setAllowedSemanticDatabase("test");
        try {
            coordinator.initialize();
        } finally {
            properties.setResetEnabled(false);
            properties.setResetConfirmation(null);
        }

        assertThat(postgresCount("semantic_gate_audit")).isZero();
        assertThat(postgresCount("semantic_document_indexes")).isZero();
        assertThat(users.count()).isEqualTo(5);
        assertThat(documents.count()).isEqualTo(5);
        assertThat(fileCount()).isEqualTo(5);
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

    private long fileCount() throws Exception {
        try (var paths = Files.list(DOCUMENT_ROOT)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }
}
