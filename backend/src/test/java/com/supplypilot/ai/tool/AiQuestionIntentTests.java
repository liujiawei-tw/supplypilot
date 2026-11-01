package com.supplypilot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.supplypilot.ai.dto.AiToolType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class AiQuestionIntentTests {
    @Test
    void classifiesSupplierServiceHoursAsDocumentSearchWithSpringAi() {
        ChatModel model = modelReturning("{\"tools\":[\"SEARCH_DOCUMENTS\"]}");
        AtomicReference<Prompt> captured = new AtomicReference<>();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            return response("{\"tools\":[\"SEARCH_DOCUMENTS\"]}");
        });

        AiQuestionIntent intent = AiQuestionIntent.classify(model,
                "SUP-003 的服務時段是哪些日子、幾點到幾點？");

        assertThat(intent.requestedTools()).containsExactly(AiToolType.SEARCH_DOCUMENTS);
        assertThat(captured.get().getInstructions().stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).map(SystemMessage::getText).findFirst().orElse(""))
                .contains("SUP-003 service hours are a document question")
                .contains("SUPPLYPILOT_INTENT_CLASSIFIER");
        assertThat(captured.get().getUserMessage().getText())
                .isEqualTo("SUP-003 的服務時段是哪些日子、幾點到幾點？");
    }

    @Test
    void invalidOrEmptyClassifierOutputFallsBackToDocumentSearch() {
        ChatModel model = modelReturning("{\"tools\":[\"WRITE_PURCHASE_ORDER\"]}");

        AiQuestionIntent invalid = AiQuestionIntent.classify(model, "更新採購單");

        assertThat(invalid.requestedTools()).containsExactly(AiToolType.SEARCH_DOCUMENTS);
        AiQuestionIntent empty = AiQuestionIntent.classify(modelReturning("{\"tools\":[]}"), "未知意圖");
        assertThat(empty.requestedTools()).containsExactly(AiToolType.SEARCH_DOCUMENTS);
    }

    @Test
    void compoundIntentKeepsAllAllowlistedReadOnlyTools() {
        ChatModel model = modelReturning("{\"tools\":[\"QUERY_INVENTORY\",\"QUERY_OPEN_PO\"]}");

        AiQuestionIntent intent = AiQuestionIntent.classify(model, "MAT-001 庫存及未收貨採購單");

        assertThat(intent.requestedTools()).containsExactlyInAnyOrder(
                AiToolType.QUERY_INVENTORY, AiToolType.QUERY_OPEN_PO);
    }

    private ChatModel modelReturning(String text) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(text));
        return model;
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
