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
import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.ai.tool.SpringAiReadTools;
import com.supplypilot.ai.usage.AiMonthlyUsageMonitor;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.service.MaterialService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
    private final AiMonthlyUsageMonitor usageMonitor = mock(AiMonthlyUsageMonitor.class);

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void localAvailabilityCheckDoesNotCallTheModel() {
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        SpringAiProviderClient provider = provider(models, mock(AiToolRegistry.class), Duration.ofSeconds(1));
        assertThat(provider.isConfigured()).isFalse();
        ChatModel model = mock(ChatModel.class);
        when(models.getIfAvailable()).thenReturn(model);
        assertThat(provider.isConfigured()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(model);
    }

    @Test
    void springAiSelectsAndExecutesOnlyTheReadOnlyToolAllowlist() {
        AiToolRegistry registry = mock(AiToolRegistry.class);
        AiToolResult toolResult = inventoryResult();
        when(registry.execute(any())).thenReturn(toolResult);
        assertThat(List.of(ToolCallbacks.from(new SpringAiReadTools(
                registry, planner(), "MAT-001 的庫存", 4))).stream()
                .map(callback -> callback.getToolDefinition().name()).sorted().toList())
                .containsExactly("EXPLAIN_RECOMMENDATION", "QUERY_INVENTORY", "QUERY_OPEN_PO",
                        "QUERY_RECOMMENDATION", "QUERY_SUPPLIER_RANKING", "SEARCH_DOCUMENTS");
        ChatModel model = fakeModel(prompt -> hasToolResponse(prompt)
                ? finalAnswer(1)
                : toolCall("QUERY_INVENTORY", "{\"materialCode\":\"MAT-001\"}"));

        AiProviderResponse response = provider(model, registry, Duration.ofSeconds(2))
                .query(new AiProviderRequest("MAT-001 的庫存是多少？"));

        assertThat(response.results()).containsExactly(toolResult);
        assertThat(response.answerSource()).isEqualTo("MODEL_EXPLANATION");
        assertThat(response.answer()).contains(toolResult.trustedFacts());
        assertThat(response.totalTokens()).isEqualTo(7);
        verify(usageMonitor).record(7);
        verify(registry).execute(new AiQueryPlan(AiToolType.QUERY_INVENTORY, null, "MAT-001", null));
    }

    @Test
    void executesTwoSpringAiToolsAndPreservesOrder() {
        AiToolRegistry registry = mock(AiToolRegistry.class);
        AiToolResult inventory = inventoryResult();
        AiToolResult orders = new AiToolResult(AiToolType.QUERY_OPEN_PO, AiQueryStatus.ANSWERED,
                "PO-001 狀態 ORDERED，總成本 200 TWD。",
                List.of(AiSourceResponse.resource("PURCHASE_ORDER", 2, "PO-001")));
        when(registry.execute(any())).thenAnswer(call -> {
            AiQueryPlan plan = call.getArgument(0);
            return plan.tool() == AiToolType.QUERY_INVENTORY ? inventory : orders;
        });
        ChatModel model = fakeModel(prompt -> {
            if (hasToolResponse(prompt)) return finalAnswer(2);
            AssistantMessage calls = AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("inventory", "function", "QUERY_INVENTORY",
                            "{\"materialCode\":\"MAT-001\"}"),
                    new AssistantMessage.ToolCall("po", "function", "QUERY_OPEN_PO",
                            "{\"materialCode\":\"MAT-001\"}"))).build();
            return new ChatResponse(List.of(new Generation(calls)));
        });

        AiProviderResponse response = provider(model, registry, Duration.ofSeconds(2))
                .query(new AiProviderRequest("MAT-001 庫存與未完成採購單"));
        assertThat(response.results()).containsExactly(inventory, orders);
        assertThat(response.answer()).contains("100 KG", "200 TWD");
    }

    @Test
    void wrongGeneratedBusinessClaimFallsBackAfterARealToolExecution() {
        AiToolRegistry registry = mock(AiToolRegistry.class);
        when(registry.execute(any())).thenReturn(inventoryResult());
        ChatModel model = fakeModel(prompt -> hasToolResponse(prompt)
                ? new ChatResponse(List.of(new Generation(new AssistantMessage("MAT-001 庫存為 999 KG。"))))
                : toolCall("QUERY_INVENTORY", "{\"materialCode\":\"MAT-001\"}"));

        AiProviderResponse response = provider(model, registry, Duration.ofSeconds(2))
                .query(new AiProviderRequest("MAT-001 的庫存是多少？"));
        assertThat(response.answerSource()).isEqualTo("BACKEND_FACTS");
        assertThat(response.answer()).isEqualTo(inventoryResult().trustedFacts());
        assertThat(response.answer()).doesNotContain("999");
    }

    @Test
    void modelCallLimitStopsRepeatedToolLoop() {
        AiToolRegistry registry = mock(AiToolRegistry.class);
        when(registry.execute(any())).thenReturn(inventoryResult());
        ChatModel looping = fakeModel(prompt -> toolCall(
                "QUERY_INVENTORY", "{\"materialCode\":\"MAT-001\"}"));
        assertProviderCode(provider(looping, registry, Duration.ofSeconds(2)),
                ErrorCode.AI_INVALID_RESPONSE, "MAT-001 庫存");
        assertProviderReason(provider(looping, registry, Duration.ofSeconds(2), 4, 1),
                "MAT-001 庫存", AiFailureReason.MODEL_CALL_LIMIT);
    }

    @Test
    void independentModelAndToolLimitsTerminateSafely() {
        AiToolRegistry registry = mock(AiToolRegistry.class);
        when(registry.execute(any())).thenReturn(inventoryResult());
        ChatModel looping = fakeModel(prompt -> toolCall(
                "QUERY_INVENTORY", "{\"materialCode\":\"MAT-001\"}"));
        assertProviderCode(provider(looping, registry, Duration.ofSeconds(2), 4, 2),
                ErrorCode.AI_INVALID_RESPONSE, "MAT-001 庫存");
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.times(2)).execute(any());

        org.mockito.Mockito.reset(registry);
        when(registry.execute(any())).thenReturn(inventoryResult());
        assertProviderCode(provider(looping, registry, Duration.ofSeconds(2), 1, 5),
                ErrorCode.AI_INVALID_RESPONSE, "MAT-001 庫存");
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.times(1)).execute(any());
        assertProviderReason(provider(looping, registry, Duration.ofSeconds(2), 1, 5),
                "MAT-001 庫存", AiFailureReason.TOOL_CALL_LIMIT);
    }

    @Test
    void forbiddenToolAndInventedMaterialCannotExecute() {
        AiToolRegistry registry = mock(AiToolRegistry.class);
        ChatModel forbidden = fakeModel(prompt -> toolCall("APPROVE_PO", "{\"id\":1}"));
        assertProviderCode(provider(forbidden, registry, Duration.ofSeconds(2)),
                ErrorCode.AI_INVALID_RESPONSE);
        ChatModel invented = fakeModel(prompt -> toolCall(
                "QUERY_INVENTORY", "{\"materialCode\":\"MAT-999\"}"));
        assertProviderCode(provider(invented, registry, Duration.ofSeconds(2)),
                ErrorCode.AI_INVALID_RESPONSE);
        assertProviderReason(provider(forbidden, registry, Duration.ofSeconds(2)),
                "採購單核准狀態", AiFailureReason.UNKNOWN_OR_FORBIDDEN_TOOL);
        assertProviderReason(provider(invented, registry, Duration.ofSeconds(2)),
                "question", AiFailureReason.UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER);
        org.mockito.Mockito.verifyNoInteractions(registry);
    }

    @Test
    void missingFinalResponseAndToolExecutionFailureHaveFixedDiagnosticReasons() {
        ChatModel missing = fakeModel(prompt -> new ChatResponse(List.of()));
        assertProviderReason(provider(missing, mock(AiToolRegistry.class), Duration.ofSeconds(2)),
                "查詢", AiFailureReason.MISSING_FINAL_MODEL_RESPONSE);

        AiToolRegistry failingRegistry = mock(AiToolRegistry.class);
        when(failingRegistry.execute(any())).thenThrow(new IllegalStateException("sensitive tool details"));
        ChatModel callsInventory = fakeModel(prompt -> toolCall(
                "QUERY_INVENTORY", "{\"materialCode\":\"MAT-001\"}"));
        assertProviderReason(provider(callsInventory, failingRegistry, Duration.ofSeconds(2)),
                "MAT-001 庫存", AiFailureReason.TOOL_EXECUTION_FAILURE);
    }

    @Test
    void providerFailureWithoutKnownCategoryUsesOtherProviderFailure() {
        ChatModel broken = fakeModel(prompt -> {
            throw new IllegalStateException("sensitive provider details");
        });
        assertProviderReason(provider(broken, mock(AiToolRegistry.class), Duration.ofSeconds(2)),
                "查詢", AiFailureReason.OTHER_PROVIDER_FAILURE);
    }

    @Test
    void supplierIdentifierDoesNotRejectAnExplicitKnownMaterialInSameQuestion() {
        AiToolRegistry registry = mock(AiToolRegistry.class);
        AiToolResult inventory = inventoryResult();
        when(registry.execute(any())).thenReturn(inventory);
        ChatModel model = fakeModel(prompt -> hasToolResponse(prompt)
                ? finalAnswer(1)
                : toolCall("QUERY_INVENTORY", "{\"materialCode\":\"MAT-001\"}"));
        SpringAiProviderClient provider = provider(model, registry, Duration.ofSeconds(2));

        AiProviderResponse response = provider.query(new AiProviderRequest(
                "SUP-001 的 MAT-001 標準交期是多久？"));

        assertThat(response.results()).containsExactly(inventory);
        verify(registry).execute(new AiQueryPlan(AiToolType.QUERY_INVENTORY, null, "MAT-001", null));
    }

    @Test
    void rejectsNaturalLanguageOrFabricatedProviderAnswersWithoutAToolExecution() {
        ChatModel model = fakeModel(prompt -> new ChatResponse(List.of(
                new Generation(new AssistantMessage("MAT-001 庫存為 999 KG")))));
        assertProviderCode(provider(model, mock(AiToolRegistry.class), Duration.ofSeconds(2)),
                ErrorCode.AI_INVALID_RESPONSE);
        assertProviderReason(provider(model, mock(AiToolRegistry.class), Duration.ofSeconds(2)),
                "查詢", AiFailureReason.NO_TOOL_EXECUTION);
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
        assertProviderCode(provider(broken, mock(AiToolRegistry.class), Duration.ofSeconds(5)),
                ErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    private SpringAiProviderClient provider(ChatModel model, AiToolRegistry registry, Duration timeout) {
        return provider(model, registry, timeout, 4, 5);
    }

    private SpringAiProviderClient provider(ChatModel model, AiToolRegistry registry,
                                             Duration timeout, int maxTools, int maxModels) {
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        return provider(models, registry, timeout, maxTools, maxModels);
    }

    private SpringAiProviderClient provider(
            ObjectProvider<ChatModel> models, AiToolRegistry registry, Duration timeout
    ) {
        return provider(models, registry, timeout, 4, 5);
    }

    private SpringAiProviderClient provider(ObjectProvider<ChatModel> models, AiToolRegistry registry,
                                            Duration timeout, int maxTools, int maxModels) {
        return new SpringAiProviderClient(models, registry,
                planner(), new AiResponseGuard(new ObjectMapper()),
                new AiProperties(timeout, 256, maxTools, maxModels, false, 1_000_000),
                executor, new SimpleMeterRegistry(), usageMonitor);
    }

    private AiQueryPlanner planner() {
        return new AiQueryPlanner();
    }

    private boolean hasToolResponse(Prompt prompt) {
        return prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
    }

    private ChatResponse finalAnswer(int count) {
        String indexes = java.util.stream.IntStream.range(0, count).mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"intro\":\"查詢結果整理如下。\",\"factIndexes\":[" + indexes + "]}"))));
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
        assertProviderCode(provider, code, "question");
    }

    private void assertProviderCode(SpringAiProviderClient provider, ErrorCode code, String message) {
        assertThatThrownBy(() -> provider.query(new AiProviderRequest(message)))
                .isInstanceOf(AiProviderException.class).extracting("errorCode").isEqualTo(code);
    }

    private void assertProviderReason(SpringAiProviderClient provider, String message, AiFailureReason reason) {
        assertThatThrownBy(() -> provider.query(new AiProviderRequest(message)))
                .isInstanceOf(AiProviderException.class)
                .extracting(exception -> ((AiProviderException) exception).getFailureReason())
                .isEqualTo(reason);
    }
}
