package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

import com.supplypilot.SupplyPilotApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Uses complete Spring Boot contexts to prove the pre-Flyway demo guard ordering. */
class DemoFlywayPreflightIntegrationTests {
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("shared_documents")
            .withCommand("--log-bin-trust-function-creators=1");
    private static Path temporaryRoot;

    @BeforeAll
    static void startMysqlAndCreateSentinels() throws Exception {
        MYSQL.start();
        temporaryRoot = Files.createTempDirectory("supplypilot-flyway-preflight-");
        execute(MYSQL, "CREATE TABLE sentinel_records (id INT PRIMARY KEY, note VARCHAR(40))");
        execute(MYSQL, "INSERT INTO sentinel_records VALUES (1, 'must-not-change')");
        MYSQL.execInContainer("mysql", "-uroot", "-ptest", "-e", "CREATE DATABASE supplypilot_demo_preflight_test");
        MYSQL.execInContainer("mysql", "-uroot", "-ptest", "-e", "CREATE DATABASE unrelated_migrations");
        MYSQL.execInContainer("mysql", "-uroot", "-ptest", "-e", "GRANT ALL PRIVILEGES ON *.* TO 'test'@'%'");
    }

    @AfterAll
    static void stopMysql() throws Exception {
        MYSQL.stop();
        if (temporaryRoot != null) {
            try (var paths = Files.walk(temporaryRoot)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }

    @Test
    void rejectsSharedDatabaseBeforeFlywayCreatesAnyApplicationSchemaOrTouchesFiles() throws Exception {
        Path sharedStorage = Files.createDirectories(temporaryRoot.resolve("demo-documents-shared-target"));
        Path sentinel = Files.writeString(sharedStorage.resolve("keep.txt"), "must-not-change");

        assertThatThrownBy(() -> start("demo", "shared_documents", sharedStorage, true))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("The allowed MySQL database is not explicitly demo-isolated");

        assertThat(queryForLong("shared_documents", "SELECT COUNT(*) FROM sentinel_records")).isOne();
        assertThat(queryForLong("shared_documents", """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'shared_documents' AND table_name = 'flyway_schema_history'
                """)).isZero();
        assertThat(queryForLong("shared_documents", """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'shared_documents' AND table_name <> 'sentinel_records'
                """)).isZero();
        assertThat(sentinel).hasContent("must-not-change");
        try (var paths = Files.list(sharedStorage)) {
            assertThat(paths).containsExactly(sentinel);
        }
    }

    @Test
    void legalDemoTargetMigratesThenSeedsInACompleteSpringBootContext() throws Exception {
        Path demoStorage = Files.createDirectories(temporaryRoot.resolve("demo-documents-preflight"));

        try (ConfigurableApplicationContext ignored = start("demo", "supplypilot_demo_preflight_test", demoStorage, true)) {
            assertThat(queryForLong("supplypilot_demo_preflight_test", """
                    SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1
                    """)).isGreaterThan(0L);
            assertThat(queryForLong("supplypilot_demo_preflight_test", """
                    SELECT COUNT(*) FROM demo_dataset_state WHERE dataset_key = 'SP-901' AND status = 'COMPLETE'
                    """)).isOne();
            assertThat(queryForLong("supplypilot_demo_preflight_test", "SELECT COUNT(*) FROM document_metadata")).isEqualTo(5L);
        }
    }

    @Test
    void nonDemoProfileStillUsesNormalFlywayMigrationPath() throws Exception {
        Path ordinaryStorage = Files.createDirectories(temporaryRoot.resolve("ordinary-documents"));

        try (ConfigurableApplicationContext ignored = start("test", "unrelated_migrations", ordinaryStorage, false)) {
            assertThat(queryForLong("unrelated_migrations", """
                    SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1
                    """)).isGreaterThan(0L);
        }
    }

    private static ConfigurableApplicationContext start(
            String profile, String database, Path storagePath, boolean enableDemoData) {
        java.util.Map<String, Object> isolatedProperties = java.util.Map.of(
                "DB_URL", jdbcUrl(database),
                "DB_USERNAME", MYSQL.getUsername(),
                "DB_PASSWORD", MYSQL.getPassword(),
                "supplypilot.demo.allowed-database", database,
                "supplypilot.document-storage.path", storagePath.toString(),
                "supplypilot.demo.account-password", "Demo-Password-For-Tests-901!",
                "supplypilot.jwt.secret", "dGVzdC1zZWNyZXQtZm9yLWZseXdheS1wcmVmbGlnaHQtMjU2LWJpdHM=");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("isolatedTestcontainer", isolatedProperties));
        return new SpringApplicationBuilder(SupplyPilotApplication.class)
                .profiles(profile)
                .web(WebApplicationType.NONE)
                .environment(environment)
                .initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("isolatedTestcontainerInitializer", isolatedProperties)))
                .properties(
                        "spring.datasource.url=" + jdbcUrl(database),
                        "spring.datasource.username=" + MYSQL.getUsername(),
                        "spring.datasource.password=" + MYSQL.getPassword(),
                        "DB_URL=" + jdbcUrl(database),
                        "DB_USERNAME=" + MYSQL.getUsername(),
                        "DB_PASSWORD=" + MYSQL.getPassword(),
                        "spring.flyway.enabled=true",
                        "supplypilot.demo-data-enabled=" + enableDemoData,
                        "supplypilot.demo.account-password=Demo-Password-For-Tests-901!",
                        "supplypilot.demo.allowed-database=" + database,
                        "supplypilot.document-storage.path=" + storagePath,
                        "supplypilot.jwt.secret=dGVzdC1zZWNyZXQtZm9yLWZseXdheS1wcmVmbGlnaHQtMjU2LWJpdHM=",
                        "supplypilot.frontend-origin=http://localhost:5173")
                .run();
    }

    private static void execute(MySQLContainer mysql, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long queryForLong(String database, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(jdbcUrl(database), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String jdbcUrl(String database) {
        return MYSQL.getJdbcUrl().replace("/shared_documents", "/" + database);
    }
}
