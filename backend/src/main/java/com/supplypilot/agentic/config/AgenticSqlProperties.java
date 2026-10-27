package com.supplypilot.agentic.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Runtime limits and isolated database credentials for the opt-in Agentic SQL experiment. */
@ConfigurationProperties(prefix = "supplypilot.agentic.sql")
public record AgenticSqlProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("PT90S") Duration questionTimeout,
        @DefaultValue("PT30S") Duration providerTimeout,
        @DefaultValue("PT15S") Duration statementTimeout,
        @DefaultValue("10") int maxToolCalls,
        @DefaultValue("12") int maxModelCalls,
        @DefaultValue("100") int maxRows,
        @DefaultValue("32") int maxColumns,
        @DefaultValue("12000") int maxResultChars,
        @DefaultValue("2048") int maxOutputTokens,
        @DefaultValue Database mysql,
        @DefaultValue Database postgres
) {
    public AgenticSqlProperties {
        mysql = mysql == null ? Database.empty() : mysql;
        postgres = postgres == null ? Database.empty() : postgres;

        requirePositive(questionTimeout, "question-timeout");
        requirePositive(providerTimeout, "provider-timeout");
        requirePositive(statementTimeout, "statement-timeout");
        requireRange(maxToolCalls, 1, 12, "max-tool-calls");
        requireRange(maxModelCalls, 1, 16, "max-model-calls");
        requireRange(maxRows, 1, 1000, "max-rows");
        requireRange(maxColumns, 1, 64, "max-columns");
        requireRange(maxResultChars, 1, 50000, "max-result-chars");
        requireRange(maxOutputTokens, 1, 8192, "max-output-tokens");
    }

    /** Fails closed when the feature is explicitly enabled without both isolated DB identities. */
    public void requireEnabledDatabaseConfiguration(
            String primaryMysqlUsername,
            String semanticPostgresUsername
    ) {
        if (!enabled) {
            return;
        }
        requireDatabase("AGENTIC_MYSQL", mysql, "jdbc:mysql:");
        requireDatabase("AGENTIC_POSTGRES", postgres, "jdbc:postgresql:");
        requireIndependentUsername("AGENTIC_MYSQL_USERNAME", mysql.username(), primaryMysqlUsername);
        requireIndependentUsername("AGENTIC_POSTGRES_USERNAME", postgres.username(), semanticPostgresUsername);
    }

    private static void requireDatabase(String prefix, Database database, String requiredJdbcPrefix) {
        if (isBlank(database.jdbcUrl()) || !database.jdbcUrl().startsWith(requiredJdbcPrefix)) {
            throw new IllegalStateException(prefix + "_JDBC_URL must be configured with a "
                    + requiredJdbcPrefix + " URL when Agentic SQL is enabled.");
        }
        if (isBlank(database.username())) {
            throw new IllegalStateException(prefix + "_USERNAME must be configured when Agentic SQL is enabled.");
        }
        if (isBlank(database.password())) {
            throw new IllegalStateException(prefix + "_PASSWORD must be configured when Agentic SQL is enabled.");
        }
    }

    private static void requireIndependentUsername(String propertyName, String agenticUsername, String applicationUsername) {
        if (!isBlank(applicationUsername) && applicationUsername.equals(agenticUsername)) {
            throw new IllegalStateException(propertyName + " must use a dedicated read-only database identity.");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero.");
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum + ".");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record Database(
            @DefaultValue("") String jdbcUrl,
            @DefaultValue("") String username,
            @DefaultValue("") String password
    ) {
        private static Database empty() {
            return new Database("", "", "");
        }
    }
}
