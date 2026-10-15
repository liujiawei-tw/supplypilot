package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.supplypilot.document.domain.DocumentChunk;
import org.junit.jupiter.api.Test;

class EvidenceValidatorTests {
    private final EvidenceValidator validator = new EvidenceValidator();

    @Test
    void highSimilarityAloneDoesNotMakeAnUnrelatedChunkSufficient() {
        RetrievedChunk unrelated = chunk("供應商報價自開立日起 30 個曆日內有效。", 0.91);

        assertThat(validator.isSemanticallySufficient(
                "這些文件是否規定冷凍海運的保險費率？", List.of(unrelated), 0.45)).isFalse();
        assertThat(validator.isSemanticallySufficient(
                "SUP-001 的付款條件是什麼？", List.of(unrelated), 0.45)).isFalse();
    }

    @Test
    void validatesSupportedProcurementQuestionsIncludingCrossLanguageQueries() {
        assertSupported("SUP-001 的 MAT-001 標準交期是多久？",
                "SUP-001 supplies MAT-001\nMAT-001 標準交期為採購單轉為 ORDERED 後 5 個曆日。", 0.82);
        assertSupported("How long is SUP-002's quotation valid?",
                "SUP-002 的報價自開立日起 30 個曆日內有效。", 0.79);
        assertSupported("MAT-001 的基本單位及儲存條件為何？",
                "MAT-001 基本單位為 KG。請存放於陰涼乾燥處，避免陽光直射。", 0.77);
        assertSupported("哪些狀況需要隔離批次？",
                "若批次識別缺失、封條破損、受到污染或物料不符，須將該批隔離。", 0.74);
        assertSupported("Which role is responsible for receiving an ordered purchase order?",
                "只有倉庫人員可收取狀態為 ORDERED 的採購單。", 0.81);
    }

    @Test
    void returnsOnlyCandidatesThatSupportTheQuestion() {
        RetrievedChunk unrelated = chunk("SUP-001 的報價自開立日起 30 個曆日內有效。", 0.93);
        RetrievedChunk supporting = chunk("SUP-001 supplies MAT-001\nMAT-001 標準交期為 ORDERED 後 5 個曆日。", 0.76);

        assertThat(validator.supportingEvidence("SUP-001 的 MAT-001 標準交期是多久？",
                List.of(unrelated, supporting), 0.45)).containsExactly(supporting);
    }

    @Test
    void answerExcerptContainsOnlySentencesThatSupportRequestedFacts() {
        String content = "文件為虛構展示資料。SUP-001 supplies MAT-001\n"
                + "MAT-001 標準交期為 ORDERED 後 5 個曆日。 本報價另有 30 個曆日有效期限。";

        assertThat(validator.supportedExcerpt("SUP-001 的 MAT-001 標準交期是多久？", content))
                .isEqualTo("MAT-001 標準交期為 ORDERED 後 5 個曆日。");
    }

    private void assertSupported(String question, String content, double score) {
        assertThat(validator.isSemanticallySufficient(
                question, List.of(chunk(content, score)), 0.45)).isTrue();
    }

    private RetrievedChunk chunk(String content, double score) {
        DocumentChunk chunk = mock(DocumentChunk.class);
        when(chunk.getContent()).thenReturn(content);
        return new RetrievedChunk(chunk, score);
    }
}
