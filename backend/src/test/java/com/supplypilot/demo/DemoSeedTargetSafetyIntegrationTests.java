package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.supplypilot.TestcontainersConfiguration;
import com.supplypilot.testsupport.FixedClockConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "supplypilot.demo-data-enabled=false",
        "supplypilot.demo.account-password=Demo-Password-For-Tests-901!",
        "supplypilot.demo.samples-path=../samples/documents",
        "supplypilot.jwt.secret=dGVzdC1zZWNyZXQtZm9yLWRlbW8tdGFyZ2V0LXRlc3QtYXQtbGVhc3QtMjU2LWJpdHM=",
        "supplypilot.frontend-origin=http://localhost:5173"
})
@ActiveProfiles("demo")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
class DemoSeedTargetSafetyIntegrationTests {
    private static final Path NON_DEMO_DOCUMENT_ROOT;
    private static final Path SENTINEL;

    static {
        try {
            Path demoParent = Files.createTempDirectory("demo-project-");
            NON_DEMO_DOCUMENT_ROOT = Files.createDirectories(demoParent.resolve("data/documents"));
            SENTINEL = Files.writeString(NON_DEMO_DOCUMENT_ROOT.resolve("keep.txt"), "must-not-change");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("supplypilot.document-storage.path", NON_DEMO_DOCUMENT_ROOT::toString);
    }

    @Autowired private DemoDatasetCoordinator coordinator;
    @Autowired private DemoProperties properties;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void wrongMysqlTargetFailsBeforeTheFirstDatasetStateWrite() throws Exception {
        properties.setAllowedDatabase("explicitly_allowed_demo");

        assertThatThrownBy(coordinator::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected MySQL database");

        assertZeroWrites();
    }

    @Test
    void nonDemoDocumentStorageFailsBeforeTheFirstDatasetStateWrite() throws Exception {
        properties.setAllowedDatabase(jdbc.queryForObject("SELECT DATABASE()", String.class));

        assertThatThrownBy(coordinator::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("document storage path not identified as demo");

        assertZeroWrites();

        properties.setResetConfirmation(DemoDatasetCoordinator.RESET_CONFIRMATION);
        assertThatThrownBy(coordinator::resetAndInitialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("document storage path not identified as demo");
        assertZeroWrites();
    }

    private void assertZeroWrites() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_dataset_state", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_metadata", Long.class)).isZero();
        assertThat(SENTINEL).hasContent("must-not-change");
        try (var paths = Files.list(NON_DEMO_DOCUMENT_ROOT)) {
            assertThat(paths).containsExactly(SENTINEL);
        }
    }
}
