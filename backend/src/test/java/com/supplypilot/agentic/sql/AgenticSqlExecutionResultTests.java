package com.supplypilot.agentic.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AgenticSqlExecutionResultTests {
    @Test
    void preservesNullSqlValuesInImmutableRows() {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("nullable_value", null);
        AgenticSqlExecutionResult result = new AgenticSqlExecutionResult(
                "tool-1", AgenticSqlDatabase.MYSQL, List.of(), List.of("nullable_value"),
                List.of(row), 1L, false, 1L, List.of());

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst()).containsKey("nullable_value");
        assertThat(result.rows().getFirst().get("nullable_value")).isNull();
        assertThat(result.rows().getFirst()).isUnmodifiable();
    }
}
