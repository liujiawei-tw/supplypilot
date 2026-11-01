package com.supplypilot.document.semantic;

import java.util.Properties;
import javax.sql.DataSource;

import com.supplypilot.document.rag.RagChunkingProperties;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.TaskType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.type.AnnotatedTypeMetadata;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SemanticProperties.class, SemanticIndexingProperties.class,
        RagChunkingProperties.class})
@EnableScheduling
public class SemanticConfiguration {
    @Bean(name = "ragDocumentEmbeddingModel")
    @ConditionalOnMissingBean(name = "ragDocumentEmbeddingModel")
    @Conditional(GeminiEmbeddingConfigured.class)
    EmbeddingModel ragDocumentEmbeddingModel(@Value("${spring.ai.google.genai.api-key:}") String apiKey,
                                             SemanticProperties properties) {
        return new TimedEmbeddingModel(embeddingModel(apiKey, properties, TaskType.RETRIEVAL_DOCUMENT),
                properties.embeddingTimeout());
    }

    @Bean(name = "ragQueryEmbeddingModel")
    @ConditionalOnMissingBean(name = "ragQueryEmbeddingModel")
    @Conditional(GeminiEmbeddingConfigured.class)
    EmbeddingModel ragQueryEmbeddingModel(@Value("${spring.ai.google.genai.api-key:}") String apiKey,
                                          SemanticProperties properties) {
        return new TimedEmbeddingModel(embeddingModel(apiKey, properties, TaskType.RETRIEVAL_QUERY),
                properties.embeddingTimeout());
    }

    @Bean(name = "semanticPostgresAccess")
    @Conditional(SemanticPostgresConfigured.class)
    SemanticPostgresAccess semanticPostgresAccess(SemanticProperties properties) {
        DataSource dataSource = semanticPostgresDataSource(properties);
        return new SemanticPostgresAccess(dataSource, new JdbcTemplate(dataSource));
    }

    @Bean(name = "ragWriteVectorStore")
    @Lazy
    @Conditional(RagStoreConfigured.class)
    PgVectorStore ragWriteVectorStore(
            @Qualifier("semanticPostgresAccess") SemanticPostgresAccess postgresAccess,
            @Qualifier("ragDocumentEmbeddingModel") EmbeddingModel embeddingModel,
            SemanticProperties properties, SemanticStore semanticStore) {
        return vectorStore(postgresAccess.jdbcTemplate(), embeddingModel, properties, semanticStore);
    }

    @Bean(name = "ragQueryVectorStore")
    @Lazy
    @Conditional(RagStoreConfigured.class)
    PgVectorStore ragQueryVectorStore(
            @Qualifier("semanticPostgresAccess") SemanticPostgresAccess postgresAccess,
            @Qualifier("ragQueryEmbeddingModel") EmbeddingModel embeddingModel,
            SemanticProperties properties, SemanticStore semanticStore) {
        return vectorStore(postgresAccess.jdbcTemplate(), embeddingModel, properties, semanticStore);
    }

    private PgVectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
            SemanticProperties properties, SemanticStore semanticStore) {
        // PgVectorStore validates the physical table at bean initialization. Keep this inside
        // the lazy factory so unavailable PostgreSQL cannot prevent the application from starting.
        semanticStore.ensureSchema();
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("public")
                .vectorTableName(SemanticStore.RAG_VECTOR_TABLE)
                .dimensions(properties.dimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.NONE)
                .removeExistingVectorStoreTable(false)
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .build();
    }

    private EmbeddingModel embeddingModel(String apiKey, SemanticProperties properties, TaskType taskType) {
        var connection = GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(apiKey).build();
        var options = GoogleGenAiTextEmbeddingOptions.builder().model(properties.modelId())
                .dimensions(properties.dimensions()).taskType(taskType).autoTruncate(false).build();
        return new GoogleGenAiTextEmbeddingModel(connection, options);
    }

    private DataSource semanticPostgresDataSource(SemanticProperties properties) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(properties.jdbcUrl());
        source.setUsername(properties.username());
        source.setPassword(properties.password());
        Properties connectionTimeouts = new Properties();
        connectionTimeouts.setProperty("connectTimeout", "5");
        connectionTimeouts.setProperty("socketTimeout", "15");
        source.setConnectionProperties(connectionTimeouts);
        return source;
    }

    record SemanticPostgresAccess(DataSource dataSource, JdbcTemplate jdbcTemplate) { }

    static class GeminiEmbeddingConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return "google-genai".equals(context.getEnvironment()
                    .getProperty("spring.ai.model.embedding.text"))
                    && org.springframework.util.StringUtils.hasText(context.getEnvironment()
                    .getProperty("spring.ai.google.genai.api-key"));
        }
    }

    static class SemanticPostgresConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return org.springframework.util.StringUtils.hasText(context.getEnvironment()
                    .getProperty("supplypilot.semantic.jdbc-url"));
        }
    }

    static class RagStoreConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return new SemanticPostgresConfigured().matches(context, metadata)
                    && new GeminiEmbeddingConfigured().matches(context, metadata);
        }
    }
}
