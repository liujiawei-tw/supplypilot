package com.supplypilot.document.semantic;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in manual smoke test. Normal automated test runs never call the paid API. */
class SemanticRealProviderSmokeTests {
    @Test
    void developerApiReturnsConfiguredDimension() {
        Assumptions.assumeTrue(Boolean.getBoolean("semantic.real-smoke"));
        String key = System.getenv("GEMINI_API_KEY");
        assertThat(key).isNotBlank();
        var connection = GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(key).build();
        var options = GoogleGenAiTextEmbeddingOptions.builder().model("gemini-embedding-001")
                .dimensions(768).taskType(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY)
                .autoTruncate(false).build();
        var model = new GoogleGenAiTextEmbeddingModel(connection, options);
        float[] output = model.call(new EmbeddingRequest(
                List.of("SupplyPilot synthetic semantic embedding smoke test."), options))
                .getResult().getOutput();
        assertThat(output).hasSize(768);
    }
}
