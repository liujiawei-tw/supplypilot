package com.supplypilot.document.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import com.supplypilot.document.rag.SpringAiRagConfiguration;

class SemanticConfigurationDataSourceIsolationTests {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class))
            .withUserConfiguration(SemanticConfiguration.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:mysql://localhost:3306/supplypilot",
                    "spring.datasource.username=supplypilot_writer",
                    "spring.datasource.password=mysql-secret",
                    "spring.datasource.hikari.initialization-fail-timeout=-1",
                    "supplypilot.semantic.jdbc-url=jdbc:postgresql://localhost:5432/supplypilot_semantic",
                    "supplypilot.semantic.username=supplypilot_semantic_writer",
                    "supplypilot.semantic.password=postgres-secret",
                    "supplypilot.semantic.model-id=test-embedding-model",
                    "supplypilot.semantic.dimensions=768",
                    "supplypilot.semantic.generation-key=test-generation-768",
                    "supplypilot.semantic.read-enabled=false",
                    "supplypilot.semantic.top-k=5",
                    "supplypilot.semantic.similarity-threshold=0.45",
                    "supplypilot.semantic.embedding-timeout=15s",
                    "supplypilot.semantic.indexing.worker-enabled=false",
                    "supplypilot.semantic.indexing.poll-interval-millis=1000",
                    "supplypilot.semantic.indexing.lease-duration=PT5M",
                    "supplypilot.semantic.indexing.max-attempts=4",
                    "supplypilot.semantic.indexing.initial-retry-delay=PT2S",
                    "supplypilot.semantic.indexing.max-retry-delay=PT1M",
                    "supplypilot.rag.chunking.chunk-size=800",
                    "supplypilot.rag.chunking.overlap-characters=80",
                    "supplypilot.rag.chunking.max-chunks=1000",
                    "supplypilot.rag.chunking.parser-timeout=30s",
                    "spring.ai.model.embedding.text=none");

    @Test
    void semanticPostgresConnectionDoesNotReplaceBootPrimaryMysqlDataSource() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DataSource.class);
            assertThat(context).hasSingleBean(JdbcTemplate.class);
            assertThat(context).doesNotHaveBean("ragDocumentEmbeddingModel");
            assertThat(context).doesNotHaveBean("ragQueryEmbeddingModel");
            assertThat(context).doesNotHaveBean("ragWriteVectorStore");
            assertThat(context).doesNotHaveBean("ragQueryVectorStore");
            assertThat(((HikariDataSource) context.getBean(DataSource.class)).getJdbcUrl())
                    .isEqualTo("jdbc:mysql://localhost:3306/supplypilot");
            JdbcTemplate primaryJdbc = context.getBean(JdbcTemplate.class);
            assertThat(primaryJdbc.getDataSource()).isSameAs(context.getBean(DataSource.class));

            SemanticConfiguration.SemanticPostgresAccess semanticAccess =
                    context.getBean("semanticPostgresAccess", SemanticConfiguration.SemanticPostgresAccess.class);
            assertThat(semanticAccess.dataSource()).isNotSameAs(context.getBean(DataSource.class));
            assertThat(semanticAccess.jdbcTemplate().getDataSource()).isSameAs(semanticAccess.dataSource());
            assertThat(semanticAccess.jdbcTemplate().getDataSource())
                    .hasFieldOrPropertyWithValue("url", "jdbc:postgresql://localhost:5432/supplypilot_semantic");
        });
    }

    @Test
    void configuredGeminiAndUnavailablePostgresDoNotBlockApplicationContextStartup() {
        contextRunner
                .withUserConfiguration(SpringAiRagConfiguration.class)
                .withPropertyValues(
                        "supplypilot.semantic.jdbc-url=jdbc:postgresql://127.0.0.1:1/supplypilot_semantic",
                        "supplypilot.semantic.read-enabled=true",
                        "spring.ai.model.embedding.text=google-genai",
                        "spring.ai.google.genai.api-key=test-only-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var beanFactory = context.getBeanFactory();
                    assertThat(beanFactory.getBeanDefinition("ragWriteVectorStore").isLazyInit()).isTrue();
                    assertThat(beanFactory.getBeanDefinition("ragQueryVectorStore").isLazyInit()).isTrue();
                    assertThat(beanFactory.getBeanDefinition("documentRagAdvisor").isLazyInit()).isTrue();
                    assertThat(beanFactory.containsSingleton("ragWriteVectorStore")).isFalse();
                    assertThat(beanFactory.containsSingleton("ragQueryVectorStore")).isFalse();
                    assertThat(beanFactory.containsSingleton("documentRagAdvisor")).isFalse();
                    assertThat(((HikariDataSource) context.getBean(DataSource.class)).getJdbcUrl())
                            .isEqualTo("jdbc:mysql://localhost:3306/supplypilot");
                });
    }
}
