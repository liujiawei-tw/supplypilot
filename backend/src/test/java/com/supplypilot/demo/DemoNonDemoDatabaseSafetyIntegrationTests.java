package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.supplypilot.testsupport.FixedClockConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "supplypilot.demo-data-enabled=false",
        "supplypilot.demo.account-password=Demo-Password-For-Tests-901!",
        "supplypilot.demo.allowed-database=shared_documents",
        "supplypilot.demo.samples-path=../samples/documents",
        "supplypilot.jwt.secret=dGVzdC1zZWNyZXQtZm9yLW5vbi1kZW1vLWRhdGFiYXNlLXRlc3QtMjU2LWJpdHM=",
        "supplypilot.frontend-origin=http://localhost:5173"
})
@ActiveProfiles("demo")
@Import(FixedClockConfiguration.class)
class DemoNonDemoDatabaseSafetyIntegrationTests {
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("shared_documents")
            .withCommand("--log-bin-trust-function-creators=1");
    private static final Path DOCUMENT_ROOT;
    private static final Path SENTINEL;

    static {
        MYSQL.start();
        try {
            DOCUMENT_ROOT = Files.createTempDirectory("supplypilot-demo-database-guard-");
            SENTINEL = Files.writeString(DOCUMENT_ROOT.resolve("keep.txt"), "must-not-change");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("supplypilot.document-storage.path", DOCUMENT_ROOT::toString);
    }

    @Autowired private DemoDatasetCoordinator coordinator;
    @Autowired private DemoProperties properties;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void refusesAnActualNonDemoDatabaseEvenWhenItIsConfiguredAsAllowed() throws Exception {
        jdbc.update("""
                INSERT INTO demo_dataset_state
                    (dataset_key, dataset_version, baseline_date, status, created_at)
                VALUES ('SENTINEL', 'keep', CURRENT_DATE, 'COMPLETE', CURRENT_TIMESTAMP)
                """);

        assertThatThrownBy(coordinator::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed MySQL database is not explicitly demo-isolated");

        properties.setResetConfirmation(DemoDatasetCoordinator.RESET_CONFIRMATION);
        assertThatThrownBy(coordinator::resetAndInitialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed MySQL database is not explicitly demo-isolated");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_dataset_state WHERE dataset_key = 'SENTINEL'", Long.class)).isOne();
        assertThat(SENTINEL).hasContent("must-not-change");
    }
}
