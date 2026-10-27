package com.supplypilot.agentic.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgenticSqlMetadataPolicyTests {
    private final AgenticSqlMetadataPolicy policy = new AgenticSqlMetadataPolicy();

    @Test
    void excludesPluralCredentialTablesAndKeepsBusinessTablesDiscoverable() {
        for (String table : new String[] {"api_keys", "access_keys", "api-key_archive", "credentials"}) {
            assertThat(policy.isTableVisible(AgenticSqlDatabase.MYSQL, null, table))
                    .as("table %s", table).isFalse();
        }
        assertThat(policy.isTableVisible(AgenticSqlDatabase.MYSQL, null, "supplier_quotes")).isTrue();
    }
}
