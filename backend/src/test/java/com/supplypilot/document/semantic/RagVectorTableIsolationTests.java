package com.supplypilot.document.semantic;

import com.supplypilot.agentic.sql.AgenticSqlDatabase;
import com.supplypilot.agentic.sql.AgenticSqlMetadataPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagVectorTableIsolationTests {
    @Test
    void pgvectorTableStaysOutsideTheAgenticSqlQueryableSemanticAllowlist() {
        AgenticSqlMetadataPolicy policy = new AgenticSqlMetadataPolicy();

        assertThat(SemanticStore.RAG_VECTOR_TABLE).startsWith("semantic_");
        assertThat(policy.isTableVisible(AgenticSqlDatabase.POSTGRESQL, "public",
                SemanticStore.RAG_VECTOR_TABLE)).isFalse();
        assertThat(policy.isTableVisible(AgenticSqlDatabase.POSTGRESQL, "public", "semantic_chunks"))
                .isTrue();
    }
}
