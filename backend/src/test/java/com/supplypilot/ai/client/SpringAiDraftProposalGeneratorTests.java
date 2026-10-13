package com.supplypilot.ai.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.ai.config.AiProperties;
import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.ai.tool.AiToolRegistry;
import com.supplypilot.ai.usage.AiMonthlyUsageMonitor;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.procurement.dto.request.CreatePurchaseOrderItemRequest;
import com.supplypilot.procurement.dto.request.CreatePurchaseOrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SpringAiDraftProposalGeneratorTests {
    @Test void forbiddenModelToolCannotExecute() {
        AiToolRegistry registry = mock(AiToolRegistry.class);
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        ChatModel model = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("forbidden", "function", "APPROVE_PO", "{\"id\":1}"))).build();
                return new ChatResponse(List.of(new Generation(message)));
            }
            @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatOptions getOptions() { return getDefaultOptions(); }
        };
        when(models.getIfAvailable()).thenReturn(model);
        CreatePurchaseOrderItemRequest item = new CreatePurchaseOrderItemRequest();
        item.setMaterialId(2L);
        item.setQuantity(BigDecimal.ONE);
        item.setUnitPrice(BigDecimal.TEN);
        CreatePurchaseOrderRequest draft = new CreatePurchaseOrderRequest();
        draft.setSupplierId(1L);
        draft.setItems(List.of(item));
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var generator = new SpringAiDraftProposalGenerator(models, registry, new AiQueryPlanner(),
                    new AiProperties(Duration.ofSeconds(10), 256, 4, 5, false, 1_000_000),
                    executor, new ObjectMapper(), new SimpleMeterRegistry(), mock(AiMonthlyUsageMonitor.class));
            assertThatThrownBy(() -> generator.generate("請建立草稿", draft))
                    .isInstanceOf(AiProviderException.class).extracting("errorCode")
                    .isEqualTo(ErrorCode.AI_INVALID_RESPONSE);
        } finally {
            executor.shutdownNow();
        }
        verifyNoInteractions(registry);
    }
}
