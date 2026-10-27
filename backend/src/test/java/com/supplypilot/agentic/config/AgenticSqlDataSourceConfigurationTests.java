package com.supplypilot.agentic.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class AgenticSqlDataSourceConfigurationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgenticSqlDataSourceConfiguration.class);

    @Test
    void featureIsDisabledByDefaultAndDoesNotCreateDatabasePools() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgenticSqlProperties.class);
            assertThat(context).doesNotHaveBean(AgenticSqlDataSources.class);
            assertThat(context.getBean(AgenticSqlProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void enabledFeatureFailsStartupWhenEitherDatabaseConfigurationIsMissing() {
        contextRunner
                .withPropertyValues("supplypilot.agentic.sql.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeMessages(context.getStartupFailure()))
                            .contains("AGENTIC_MYSQL_JDBC_URL");
                });

        contextRunner
                .withPropertyValues(
                        "supplypilot.agentic.sql.enabled=true",
                        "supplypilot.agentic.sql.mysql.jdbc-url=jdbc:mysql://localhost:3306/supplypilot",
                        "supplypilot.agentic.sql.mysql.username=agentic_mysql_ro",
                        "supplypilot.agentic.sql.mysql.password=mysql-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeMessages(context.getStartupFailure()))
                            .contains("AGENTIC_POSTGRES_JDBC_URL");
                });
    }

    @Test
    void enabledPoolsUseDedicatedCredentialsAndAreReadOnlyWithLowConnectionLimits() {
        contextRunner
                .withPropertyValues(
                        "supplypilot.agentic.sql.enabled=true",
                        "supplypilot.agentic.sql.mysql.jdbc-url=jdbc:mysql://localhost:3306/supplypilot",
                        "supplypilot.agentic.sql.mysql.username=agentic_mysql_ro",
                        "supplypilot.agentic.sql.mysql.password=mysql-secret",
                        "supplypilot.agentic.sql.postgres.jdbc-url=jdbc:postgresql://localhost:5432/supplypilot_semantic",
                        "supplypilot.agentic.sql.postgres.username=agentic_postgres_ro",
                        "supplypilot.agentic.sql.postgres.password=postgres-secret",
                        "spring.datasource.username=supplypilot_writer",
                        "supplypilot.semantic.username=supplypilot_semantic_writer")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AgenticSqlDataSources.class);

                    AgenticSqlDataSources dataSources = context.getBean(AgenticSqlDataSources.class);
                    HikariDataSource mysql = (HikariDataSource) ReflectionTestUtils.getField(dataSources, "mysql");
                    HikariDataSource postgres = (HikariDataSource) ReflectionTestUtils.getField(dataSources, "postgres");

                    assertThat(mysql.getJdbcUrl()).startsWith("jdbc:mysql:");
                    assertThat(mysql.getUsername()).isEqualTo("agentic_mysql_ro");
                    assertReadOnlyPool(mysql);
                    assertThat(postgres.getJdbcUrl()).startsWith("jdbc:postgresql:");
                    assertThat(postgres.getUsername()).isEqualTo("agentic_postgres_ro");
                    assertReadOnlyPool(postgres);
                });
    }

    @Test
    void enabledPoolsRejectApplicationDatabaseUsernames() {
        contextRunner
                .withPropertyValues(
                        "supplypilot.agentic.sql.enabled=true",
                        "supplypilot.agentic.sql.mysql.jdbc-url=jdbc:mysql://localhost:3306/supplypilot",
                        "supplypilot.agentic.sql.mysql.username=supplypilot_writer",
                        "supplypilot.agentic.sql.mysql.password=mysql-secret",
                        "supplypilot.agentic.sql.postgres.jdbc-url=jdbc:postgresql://localhost:5432/supplypilot_semantic",
                        "supplypilot.agentic.sql.postgres.username=agentic_postgres_ro",
                        "supplypilot.agentic.sql.postgres.password=postgres-secret",
                        "spring.datasource.username=supplypilot_writer",
                        "supplypilot.semantic.username=supplypilot_semantic_writer")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeMessages(context.getStartupFailure()))
                            .contains("AGENTIC_MYSQL_USERNAME must use a dedicated read-only database identity.");
                });
    }

    private static void assertReadOnlyPool(HikariDataSource dataSource) {
        assertThat(dataSource.isReadOnly()).isTrue();
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(2);
        assertThat(dataSource.getMinimumIdle()).isZero();
        assertThat(dataSource.getInitializationFailTimeout()).isNegative();
    }

    private static String causeMessages(Throwable throwable) {
        return Stream.iterate(throwable, cause -> cause != null, Throwable::getCause)
                .map(Throwable::getMessage)
                .filter(message -> message != null)
                .collect(Collectors.joining("\n"));
    }
}
