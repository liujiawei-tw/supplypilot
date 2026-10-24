package com.supplypilot.document.semantic;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.type.AnnotatedTypeMetadata;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SemanticProperties.class, SemanticIndexingProperties.class})
@EnableScheduling
public class SemanticConfiguration {
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @Conditional(GeminiEmbeddingConfigured.class)
    EmbeddingModel geminiEmbeddingModel(@Value("${spring.ai.google.genai.api-key:}") String apiKey,
                                       SemanticProperties properties) {
        var connection = GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(apiKey).build();
        var options = GoogleGenAiTextEmbeddingOptions.builder().model(properties.modelId())
                .dimensions(properties.dimensions()).autoTruncate(false).build();
        return new GoogleGenAiTextEmbeddingModel(connection, options);
    }

    static class GeminiEmbeddingConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return "google-genai".equals(context.getEnvironment()
                    .getProperty("spring.ai.model.embedding.text"))
                    && org.springframework.util.StringUtils.hasText(context.getEnvironment()
                    .getProperty("spring.ai.google.genai.api-key"));
        }
    }
}
