package com.supplypilot.document.semantic;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import com.google.genai.Client;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in manual smoke test. Normal automated test runs never call the paid API. */
class SemanticRealProviderSmokeTests {
    private static final String CHAT_SMOKE_RESPONSE = "GEMINI_CHAT_SMOKE_OK";

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

    @Test
    void developerApiReturnsChatResponse() {
        Assumptions.assumeTrue(Boolean.getBoolean("semantic.real-smoke"));
        String key = System.getenv("GEMINI_API_KEY");
        assertThat(key).isNotBlank();
        String configuredModel = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.6-flash");
        GoogleGenAiChatModel.ChatModel modelName = java.util.Arrays.stream(GoogleGenAiChatModel.ChatModel.values())
                .filter(candidate -> candidate.getValue().equals(configuredModel))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported GEMINI_MODEL"));
        try (Client client = Client.builder().apiKey(key).build()) {
            var options = GoogleGenAiChatOptions.builder().model(modelName).maxOutputTokens(128)
                    .thinkingLevel(GoogleGenAiThinkingLevel.LOW).includeThoughts(false).build();
            var chatModel = GoogleGenAiChatModel.builder().genAiClient(client).options(options).build();
            String response = ChatClient.builder(chatModel).build().prompt()
                    .user("Reply with the exact token " + CHAT_SMOKE_RESPONSE + ". This is a synthetic provider smoke test.")
                    .call().content();
            assertThat(response).contains(CHAT_SMOKE_RESPONSE);
        }
    }
}
