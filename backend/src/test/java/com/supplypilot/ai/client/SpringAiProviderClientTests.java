package com.supplypilot.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.ai.config.AiProperties;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.ai.service.AiResponseGuard;
import com.supplypilot.ai.tool.AiQueryPlan;
import com.supplypilot.ai.tool.AiToolRegistry;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.ai.tool.SpringAiReadTools;
import com.supplypilot.common.exception.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.support.ToolCallbacks;

class SpringAiProviderClientTests {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void springAiSelectsAndExecutesOnlyTheReadOnlyToolAllowlist() {
        AiToolRegistry registry = mock(AiToolRegistry.class);
        AiToolResult toolResult = inventoryResult();
        when(registry.execute(any())).thenReturn(toolResult);
        assertThat(List.of(ToolCallbacks.from(new SpringAiReadTools(registry, 1))).stream()
                .map(callback -> callback.getToolDefinition().name()).sorted().toList())
                .containsExactly("EXPLAIN_RECOMMENDATION", "QUERY_INVENTORY", "QUERY_OPEN_PO",
                        "QUERY_RECOMMENDATION", "QUERY_SUPPLIER_RANKING", "SEARCH_DOCUMENTS");
        ChatModel model = fakeModel(prompt -> toolCall(
                "QUERY_INVENTORY", "{\"materialCode\":\"MAT-001\"}"));

        AiProviderResponse response = provider(model, registry, Duration.ofSeconds(2))
                .query(new AiProviderRequest("MAT-001 的庫存是多少？"));

        assertThat(response.result()).isEqualTo(toolResult);
        verify(registry).execute(new AiQueryPlan(AiToolType.QUERY_INVENTORY, null, "MAT-001", null));
    }

    @Test
    void rejectsNaturalLanguageOrFabricatedProviderAnswersWithoutAToolExecution() {
        ChatModel model = prompt -> new ChatResponse(List.of(
                new Generation(new AssistantMessage("MAT-001 庫存為 999 KG"))));
        assertProviderCode(provider(model, mock(AiToolRegistry.class), Duration.ofSeconds(2)),
                ErrorCode.AI_INVALID_RESPONSE);
    }

    @Test
    void mapsMissingProviderFailureAndTimeout() {
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        SpringAiProviderClient unavailable = provider(absent, mock(AiToolRegistry.class), Duration.ofMillis(50));
        assertProviderCode(unavailable, ErrorCode.AI_PROVIDER_UNAVAILABLE);

        ChatModel slow = prompt -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("late"))));
        };
        assertProviderCode(provider(slow, mock(AiToolRegistry.class), Duration.ofMillis(25)), ErrorCode.AI_TIMEOUT);

        ChatModel broken = fakeModel(prompt -> {
            throw new IllegalStateException("provider credential must not leak");
        });
        assertProviderCode(provider(broken, mock(AiToolRegistry.class), Duration.ofSeconds(1)),
                ErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    private SpringAiProviderClient provider(ChatModel model, AiToolRegistry registry, Duration timeout) {
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        return provider(models, registry, timeout);
    }

    private SpringAiProviderClient provider(
            ObjectProvider<ChatModel> models, AiToolRegistry registry, Duration timeout
    ) {
        return new SpringAiProviderClient(models, registry,
                new AiResponseGuard(new ObjectMapper()), new AiProperties(timeout, 256, 1),
                executor, new SimpleMeterRegistry());
    }

    private ChatResponse toolCall(String name, String arguments) {
        AssistantMessage output = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", name, arguments))).build();
        return new ChatResponse(List.of(new Generation(output)), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(5, 2, 7)).build());
    }

    private ChatModel fakeModel(Function<Prompt, ChatResponse> response) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return response.apply(prompt);
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                return getDefaultOptions();
            }
        };
    }

    private AiToolResult inventoryResult() {
        return new AiToolResult(AiToolType.QUERY_INVENTORY, AiQueryStatus.ANSWERED,
                "MAT-001 的目前庫存為 100 KG。",
                List.of(AiSourceResponse.resource("INVENTORY", 1, "MAT-001")));
    }

    private void assertProviderCode(SpringAiProviderClient provider, ErrorCode code) {
        assertThatThrownBy(() -> provider.query(new AiProviderRequest("question")))
                .isInstanceOf(AiProviderException.class).extracting("errorCode").isEqualTo(code);
    }
}
