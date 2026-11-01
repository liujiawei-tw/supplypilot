package com.supplypilot.document.semantic;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Exercises the Spring AI 2.0.1 PgVectorStore against the same Flyway schema as the application. */
@Testcontainers(disabledWithoutDocker = true)
class SpringAiPgVectorStoreIntegrationTests {
    private static final int DIMENSIONS = 3;
    private static final String VECTOR_TABLE = "semantic_rag_vectors";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static DataSource semanticPostgresDataSource;
    private static JdbcTemplate semanticPostgresJdbcTemplate;
    private static FakeEmbeddingModel embeddingModel;

    @BeforeAll
    static void migrateSemanticDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        semanticPostgresDataSource = dataSource;
        semanticPostgresJdbcTemplate = new JdbcTemplate(semanticPostgresDataSource);
        embeddingModel = new FakeEmbeddingModel();

        Flyway.configure()
                .dataSource(semanticPostgresDataSource)
                .locations("classpath:db/semantic")
                .table("semantic_flyway_history")
                .placeholders(Map.of("ragVectorDimensions", Integer.toString(DIMENSIONS)))
                .load()
                .migrate();
    }

    @Test
    void writesSearchesAndFiltersWithDedicatedPostgresJdbcTemplateWithoutDroppingExistingData() {
        assertThat(semanticPostgresJdbcTemplate.getDataSource()).isSameAs(semanticPostgresDataSource);
        assertThat(semanticPostgresJdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(POSTGRES.getDatabaseName());

        seedLegacySemanticRow();
        UUID retainedId = UUID.randomUUID();
        semanticPostgresJdbcTemplate.update("""
                INSERT INTO semantic_rag_vectors (id, content, metadata, embedding)
                VALUES (?, ?, CAST(? AS json), CAST(? AS vector))
                """, retainedId, "retained vector", "{\"retained\":true}", "[0,0,1]");

        PgVectorStore vectorStore = PgVectorStore.builder(semanticPostgresJdbcTemplate, embeddingModel)
                .schemaName("public")
                .vectorTableName(VECTOR_TABLE)
                .dimensions(DIMENSIONS)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.NONE)
                .removeExistingVectorStoreTable(false)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .build();

        assertThat(semanticPostgresJdbcTemplate.queryForObject(
                "SELECT count(*) FROM semantic_rag_vectors WHERE id = ?", Integer.class, retainedId))
                .isEqualTo(1);
        assertThat(semanticPostgresJdbcTemplate.queryForObject(
                "SELECT count(*) FROM semantic_chunks WHERE document_id = 99001", Integer.class))
                .isEqualTo(1);

        UUID bestId = UUID.randomUUID();
        UUID closeId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        vectorStore.add(List.of(
                Document.builder().id(bestId.toString()).text("best purchase policy")
                        .metadata(Map.of("segment", "allowed", "documentId", 101,
                                "documentVersion", "v3", "supplierCode", "SUP-101")).build(),
                Document.builder().id(closeId.toString()).text("close purchase policy")
                        .metadata(Map.of("segment", "allowed", "documentId", 102,
                                "documentVersion", "v2")).build(),
                Document.builder().id(otherTenantId.toString()).text("best restricted policy")
                        .metadata(Map.of("segment", "restricted", "documentId", 103,
                                "documentVersion", "v1")).build()));

        var filter = new FilterExpressionBuilder().eq("segment", "allowed").build();
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query("best query")
                .topK(10)
                .similarityThreshold(0.2)
                .filterExpression(filter)
                .build());

        assertThat(results).extracting(Document::getId).containsExactly(bestId.toString(), closeId.toString());
        assertThat(results.getFirst().getText()).isEqualTo("best purchase policy");
        assertThat(results.getFirst().getScore()).isCloseTo(1.0, within(0.0001));
        assertThat(results.getFirst().getMetadata())
                .containsEntry("documentVersion", "v3")
                .containsEntry("supplierCode", "SUP-101");
        assertThat(results.getFirst().getMetadata().get("documentId").toString()).isEqualTo("101");

        assertThat(semanticPostgresJdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_indexes
                 WHERE schemaname = 'public' AND tablename = 'semantic_rag_vectors'
                   AND indexdef ILIKE '%embedding%'
                """, Integer.class)).isZero();
    }

    @Test
    void publishesACompatibilityProjectionForAgenticSqlFromTheSpringAiVectorRecord() {
        SemanticProperties properties = new SemanticProperties(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), "fake-embedding", DIMENSIONS, "spring-ai-test-generation", false,
                5, 0.45, Duration.ofSeconds(15));
        SemanticStore lifecycle = new SemanticStore(properties);
        lifecycle.ensureGeneration();

        String content = "approved purchasing terms";
        String sourceHash = "generation-source-hash";
        UUID id = UUID.randomUUID();
        semanticPostgresJdbcTemplate.update("""
                INSERT INTO semantic_rag_vectors (id, content, metadata, embedding)
                VALUES (?, ?, CAST(? AS json), CAST(? AS vector))
                """, id, content, """
                {"documentId":99002,"documentVersion":"v1","generationKey":"spring-ai-test-generation",
                 "documentGeneration":4,"sourceHash":"generation-source-hash","chunkIndex":0,
                 "startOffset":0,"endOffset":25}
                """, "[1,0,0]");

        lifecycle.mark(99002L, "v1", "PROCESSING", null, sourceHash, 1, 4);
        assertThat(lifecycle.publish(99002L, "v1", sourceHash, 4,
                List.of(new SemanticStore.SafeChunk(0, 0, content.length(), content)))).isTrue();

        assertThat(semanticPostgresJdbcTemplate.queryForObject("""
                SELECT content FROM semantic_chunks
                 WHERE document_id = 99002 AND document_version = 'v1'
                   AND generation_key = 'spring-ai-test-generation' AND chunk_index = 0
                """, String.class)).isEqualTo(content);
        assertThat(semanticPostgresJdbcTemplate.queryForObject("""
                SELECT vector_dims(embedding) FROM semantic_chunks
                 WHERE document_id = 99002 AND document_version = 'v1'
                   AND generation_key = 'spring-ai-test-generation' AND chunk_index = 0
                """, Integer.class)).isEqualTo(DIMENSIONS);
    }

    private static void seedLegacySemanticRow() {
        semanticPostgresJdbcTemplate.update("""
                INSERT INTO semantic_generations(generation_key, model_id, dimensions, chunking_version)
                VALUES ('legacy-test', 'legacy-model', ?, 'legacy-v1')
                """, DIMENSIONS);
        semanticPostgresJdbcTemplate.update("""
                INSERT INTO semantic_document_indexes(document_id, document_version, generation_key,
                    status, source_hash, expected_chunk_count)
                VALUES (99001, 'v1', 'legacy-test', 'READY', 'legacy-hash', 1)
                """);
        semanticPostgresJdbcTemplate.update("""
                INSERT INTO semantic_chunks(document_id, document_version, generation_key, chunk_index,
                    start_offset, end_offset, content, embedding)
                VALUES (99001, 'v1', 'legacy-test', 0, 0, 6, 'legacy', CAST('[0,0,1]' AS vector))
                """);
    }

    private static class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = request.getInstructions().stream()
                    .map(text -> new Embedding(vector(text), 0))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        private float[] vector(String text) {
            String normalized = text.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("best")) return new float[] {1.0f, 0.0f, 0.0f};
            if (normalized.contains("close")) return new float[] {0.8f, 0.6f, 0.0f};
            if (normalized.contains("far")) return new float[] {0.0f, 1.0f, 0.0f};
            return new float[] {0.0f, 0.0f, 1.0f};
        }
    }
}
