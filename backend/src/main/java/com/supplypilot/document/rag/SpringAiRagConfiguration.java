package com.supplypilot.document.rag;

import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.document.semantic.SemanticProperties;

@Configuration(proxyBeanMethods = false)
public class SpringAiRagConfiguration {

    @Bean
    @Lazy
    @Conditional(SemanticRagConfigured.class)
    RetrievalAugmentationAdvisor documentRagAdvisor(
            @Qualifier("ragQueryVectorStore") PgVectorStore vectorStore,
            SemanticProperties properties, AiQueryPlanner queryPlanner) {
        var queryTransformer = new SupplierScopedRagQueryTransformer(queryPlanner, properties);

        var retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(10)
                .similarityThreshold(properties.similarityThreshold())
                .filterExpression(queryTransformer::publishedApprovedGenerationFilter)
                .build();

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .queryTransformers(queryTransformer)
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(false).build())
                .build();
    }

    static class SemanticRagConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            return org.springframework.util.StringUtils.hasText(
                    environment.getProperty("supplypilot.semantic.jdbc-url"))
                    && "google-genai".equals(environment.getProperty("spring.ai.model.embedding.text"))
                    && org.springframework.util.StringUtils.hasText(
                    environment.getProperty("spring.ai.google.genai.api-key"));
        }
    }
}
