package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final String FLYWAY_DATASOURCE_PRIMARY = "supplypilot_demo_flyway_datasource_guard";
    private static final String FLYWAY_DATASOURCE_TARGET = "shared_flyway_datasource_target";
    private static final String FLYWAY_SCHEMAS_PRIMARY = "supplypilot_demo_flyway_schemas_guard";
    private static final String FLYWAY_SCHEMAS_TARGET = "shared_flyway_schemas_target";
    private static final String FLYWAY_DEFAULT_SCHEMA_PRIMARY = "supplypilot_demo_flyway_default_guard";
    private static final String FLYWAY_DEFAULT_SCHEMA_TARGET = "shared_flyway_default_target";
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
        for (String database : List.of(
                "supplypilot_demo_preflight_test",
                "unrelated_migrations",
                FLYWAY_DATASOURCE_PRIMARY,
                FLYWAY_DATASOURCE_TARGET,
                FLYWAY_SCHEMAS_PRIMARY,
                FLYWAY_SCHEMAS_TARGET,
                FLYWAY_DEFAULT_SCHEMA_PRIMARY,
                FLYWAY_DEFAULT_SCHEMA_TARGET)) {
            MYSQL.execInContainer("mysql", "-uroot", "-ptest", "-e", "CREATE DATABASE " + database);
        }
        MYSQL.execInContainer("mysql", "-uroot", "-ptest", "-e", "GRANT ALL PRIVILEGES ON *.* TO 'test'@'%'");
        for (String database : List.of(
                FLYWAY_DATASOURCE_TARGET, FLYWAY_SCHEMAS_TARGET, FLYWAY_DEFAULT_SCHEMA_TARGET)) {
            execute(database, "CREATE TABLE sentinel_records (id INT PRIMARY KEY, note VARCHAR(40))");
            execute(database, "INSERT INTO sentinel_records VALUES (1, 'must-not-change')");
        }
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
    void rejectsSeparateNonDemoFlywayDataSourceBeforeEitherDatabaseOrFilesAreChanged() throws Exception {
        Path storage = Files.createDirectories(temporaryRoot.resolve("demo-documents-flyway-datasource-guard"));
        Path sentinel = Files.writeString(storage.resolve("keep.txt"), "must-not-change");
        DatabaseSnapshot primaryBefore = snapshot(FLYWAY_DATASOURCE_PRIMARY);
        DatabaseSnapshot migrationBefore = snapshot(FLYWAY_DATASOURCE_TARGET);

        assertThatThrownBy(() -> start(
                "demo",
                FLYWAY_DATASOURCE_PRIMARY,
                storage,
                false,
                Map.of(
                        "spring.flyway.url", jdbcUrl(FLYWAY_DATASOURCE_TARGET),
                        "spring.flyway.user", MYSQL.getUsername(),
                        "spring.flyway.password", MYSQL.getPassword())))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Refusing Flyway migration for unexpected MySQL database: "
                        + FLYWAY_DATASOURCE_TARGET);

        assertUntouched(FLYWAY_DATASOURCE_PRIMARY, primaryBefore);
        assertUntouched(FLYWAY_DATASOURCE_TARGET, migrationBefore);
        assertSentinelFileUntouched(storage, sentinel);
    }

    @Test
    void rejectsNonDemoFlywaySchemasBeforeEitherDatabaseOrFilesAreChanged() throws Exception {
        Path storage = Files.createDirectories(temporaryRoot.resolve("demo-documents-flyway-schemas-guard"));
        Path sentinel = Files.writeString(storage.resolve("keep.txt"), "must-not-change");
        DatabaseSnapshot primaryBefore = snapshot(FLYWAY_SCHEMAS_PRIMARY);
        DatabaseSnapshot migrationBefore = snapshot(FLYWAY_SCHEMAS_TARGET);

        assertThatThrownBy(() -> start(
                "demo",
                FLYWAY_SCHEMAS_PRIMARY,
                storage,
                false,
                Map.of("spring.flyway.schemas", FLYWAY_SCHEMAS_PRIMARY + "," + FLYWAY_SCHEMAS_TARGET)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Refusing Flyway migration because configured schema is not the allowed demo database: "
                        + FLYWAY_SCHEMAS_TARGET);

        assertUntouched(FLYWAY_SCHEMAS_PRIMARY, primaryBefore);
        assertUntouched(FLYWAY_SCHEMAS_TARGET, migrationBefore);
        assertSentinelFileUntouched(storage, sentinel);
    }

    @Test
    void rejectsNonDemoFlywayDefaultSchemaBeforeEitherDatabaseOrFilesAreChanged() throws Exception {
        Path storage = Files.createDirectories(temporaryRoot.resolve("demo-documents-flyway-default-schema-guard"));
        Path sentinel = Files.writeString(storage.resolve("keep.txt"), "must-not-change");
        DatabaseSnapshot primaryBefore = snapshot(FLYWAY_DEFAULT_SCHEMA_PRIMARY);
        DatabaseSnapshot migrationBefore = snapshot(FLYWAY_DEFAULT_SCHEMA_TARGET);

        assertThatThrownBy(() -> start(
                "demo",
                FLYWAY_DEFAULT_SCHEMA_PRIMARY,
                storage,
                false,
                Map.of("spring.flyway.default-schema", FLYWAY_DEFAULT_SCHEMA_TARGET)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Refusing Flyway migration because configured default schema is not the allowed demo database: "
                        + FLYWAY_DEFAULT_SCHEMA_TARGET);

        assertUntouched(FLYWAY_DEFAULT_SCHEMA_PRIMARY, primaryBefore);
        assertUntouched(FLYWAY_DEFAULT_SCHEMA_TARGET, migrationBefore);
        assertSentinelFileUntouched(storage, sentinel);
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
        return start(profile, database, storagePath, enableDemoData, Map.of());
    }

    private static ConfigurableApplicationContext start(
            String profile,
            String database,
            Path storagePath,
            boolean enableDemoData,
            Map<String, Object> overrides) {
        Map<String, Object> isolatedProperties = new LinkedHashMap<>();
        isolatedProperties.put("DB_URL", jdbcUrl(database));
        isolatedProperties.put("DB_USERNAME", MYSQL.getUsername());
        isolatedProperties.put("DB_PASSWORD", MYSQL.getPassword());
        isolatedProperties.put("supplypilot.demo.allowed-database", database);
        isolatedProperties.put("supplypilot.document-storage.path", storagePath.toString());
        isolatedProperties.put("supplypilot.demo.account-password", "Demo-Password-For-Tests-901!");
        isolatedProperties.put(
                "supplypilot.jwt.secret", "dGVzdC1zZWNyZXQtZm9yLWZseXdheS1wcmVmbGlnaHQtMjU2LWJpdHM=");
        isolatedProperties.putAll(overrides);
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

    private static void assertUntouched(String database, DatabaseSnapshot before) throws SQLException {
        assertThat(snapshot(database)).isEqualTo(before);
        assertThat(before.tables()).doesNotContain("flyway_schema_history");
    }

    private static void assertSentinelFileUntouched(Path storage, Path sentinel) throws Exception {
        assertThat(sentinel).hasContent("must-not-change");
        try (var paths = Files.list(storage)) {
            assertThat(paths).containsExactly(sentinel);
        }
    }

    private static DatabaseSnapshot snapshot(String database) throws SQLException {
        List<String> tables = queryForStrings(database, """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() ORDER BY table_name
                """);
        List<String> sentinelRows = tables.contains("sentinel_records")
                ? queryForStrings(database, "SELECT CONCAT(id, ':', note) FROM sentinel_records ORDER BY id")
                : List.of();
        return new DatabaseSnapshot(tables, sentinelRows);
    }

    private static void execute(MySQLContainer mysql, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void execute(String database, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(jdbcUrl(database), MYSQL.getUsername(), MYSQL.getPassword());
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

    private static List<String> queryForStrings(String database, String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(jdbcUrl(database), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (result.next()) values.add(result.getString(1));
            return values;
        }
    }

    private static String jdbcUrl(String database) {
        return MYSQL.getJdbcUrl().replace("/shared_documents", "/" + database);
    }

    private record DatabaseSnapshot(List<String> tables, List<String> sentinelRows) { }
}
