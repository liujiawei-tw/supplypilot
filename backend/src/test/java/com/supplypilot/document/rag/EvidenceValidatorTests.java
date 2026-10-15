package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void topicMentionsWithoutTheRequestedRateAreInsufficient() {
        RetrievedChunk topicOnly = chunk(
                "冷凍貨物應採海運方式。保險申請應附上提單及包裝清單。", 0.94);

        assertThat(validator.isSemanticallySufficient(
                "冷凍海運的保險費率是多少？", List.of(topicOnly), 0.45)).isFalse();
    }

    @Test
    void matchingTopicsAndIdentifiersMustDescribeTheSameMaterialFact() {
        RetrievedChunk differentMaterial = chunk(
                "SUP-001 供應商供應 MAT-001 及 MAT-002。MAT-002 標準交期為 ORDERED 後 7 個曆日。", 0.93);

        assertThat(validator.isSemanticallySufficient(
                "SUP-001 的 MAT-001 交期是多少？", List.of(differentMaterial), 0.45)).isFalse();
    }

    @Test
    void anUpdateCadenceDoesNotSupportAnUndeterminedDeliveryLeadTime() {
        RetrievedChunk source = chunk(
                "MAT-001 的標準交期尚未確定，每 3 個曆日更新一次交期狀態。", 0.92);

        assertThat(validator.isSemanticallySufficient(
                "MAT-001 標準交期是多少？", List.of(source), 0.45)).isFalse();
        assertThat(validator.supportedExcerpt(
                "MAT-001 標準交期是多少？", source.chunk().getContent())).isEmpty();
    }

    @Test
    void aDifferentClaimsRateDoesNotSupportFrozenSeaFreightInsuranceRate() {
        RetrievedChunk source = chunk(
                "冷凍海運貨物的保險費率尚未訂定，其他貨物的理賠費率為 0.3%。", 0.95);

        assertThat(validator.isSemanticallySufficient(
                "冷凍海運貨物的保險費率是多少？", List.of(source), 0.45)).isFalse();
        assertThat(validator.supportedExcerpt(
                "冷凍海運貨物的保險費率是多少？", source.chunk().getContent())).isEmpty();
    }

    @Test
    void materialDeliveryAnswerExcludesAnotherMaterialsDeliveryClause() {
        String question = "SUP-001 的 MAT-001 標準交期是多少？";
        String sourceText = "SUP-001 的 MAT-001 交期為 ORDERED 後 5 個曆日；"
                + "MAT-002 交期為 7 個曆日。";
        RetrievedChunk source = chunk(sourceText, 0.9);

        assertThat(validator.isSemanticallySufficient(question, List.of(source), 0.45)).isTrue();
        assertThat(validator.supportedExcerpt(question, sourceText))
                .contains("MAT-001 交期為 ORDERED 後 5 個曆日")
                .doesNotContain("MAT-002", "7 個曆日");
    }

    @Test
    void materialRelationSelectsOnlyTheMatchingFactWhenSeveralMaterialsShareAChunk() {
        String content = "SUP-001 供應商供應 MAT-001 及 MAT-002。"
                + "MAT-001 標準交期為 ORDERED 後 5 個曆日。"
                + "MAT-002 標準交期為 ORDERED 後 7 個曆日。";
        RetrievedChunk source = chunk(content, 0.88);

        assertThat(validator.supportedExcerpt("SUP-001 的 MAT-001 交期是多少？", content))
                .contains("MAT-001 標準交期為 ORDERED 後 5 個曆日。")
                .doesNotContain("MAT-002 標準交期");
        assertThat(validator.isSemanticallySufficient(
                "SUP-001 的 MAT-001 交期是多少？", List.of(source), 0.45)).isTrue();
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
        assertSupported("SUP-001's standard lead time for MAT-001?",
                "SUP-001 supplies MAT-001. The standard lead time for MAT-001 is 5 calendar days after ORDERED.", 0.76);
        String rateQuestion = "冷凍海運的保險費率是多少？";
        String rateSource = "冷凍海運貨物保險費率為貨值的 0.3%。";
        assertThat(validator.supportedExcerpt(rateQuestion, rateSource)).isEqualTo(rateSource);
        assertSupported(rateQuestion, rateSource, 0.76);
        assertSupported("What is the standard delivery time for MAT-001?",
                "MAT-001 標準交期為採購單轉為 ORDERED 後 5 個曆日。", 0.76);
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

    @Test
    void realQualitySampleKeepsAllQuarantineConditionsAndExcludesQuestionHints() throws IOException {
        String content = Files.readString(Path.of("..", "samples", "documents", "quality-requirement-v1.txt"));
        String question = "品質要求中哪些狀況需要隔離批次？";
        RetrievedChunk source = chunk(content, 0.9);

        assertThat(validator.isSemanticallySufficient(question, List.of(source), 0.45)).isTrue();
        assertThat(validator.supportedExcerpt(question, content))
                .contains("若批次缺少批次識別、封條破損、受到污染或物料不符，須將該批隔離")
                .doesNotContain("問答：", "預期依據：");
        assertThat(new ExtractiveAnswerProvider(validator).answer(question, List.of(source)))
                .contains("缺少批次識別", "封條破損", "受到污染", "物料不符", "隔離")
                .doesNotContain("問答：", "預期依據：");
    }

    @Test
    void chineseConditionalEnumerationKeepsAllConditionsWithSharedAction() {
        assertSupported("哪些情況應停止作業？", "若發生 A、B、C 或 D，應停止作業。", 0.9);
        assertThat(validator.supportedExcerpt("哪些情況應停止作業？", "若發生 A、B、C 或 D，應停止作業。"))
                .contains("A、B、C 或 D", "應停止作業");
    }

    @Test
    void englishConditionalEnumerationKeepsAllConditionsWithSharedAction() {
        String source = "If the seal is broken, the batch is contaminated, or the material is incorrect, "
                + "quarantine the batch.";
        assertSupported("When should the batch be quarantined?", source, 0.9);
        assertThat(validator.supportedExcerpt("When should the batch be quarantined?", source))
                .contains("the seal is broken", "the batch is contaminated", "the material is incorrect",
                        "quarantine the batch");
    }

    @Test
    void semicolonSeparatedConditionsKeepTheirSharedDisposition() {
        String source = "If the seal is broken; if contamination is confirmed, quarantine the batch.";
        String excerpt = validator.supportedExcerpt("When should the batch be quarantined?", source);

        assertThat(excerpt).contains("If the seal is broken", "if contamination is confirmed",
                "quarantine the batch");
    }

    @Test
    void neighboringConditionAndActionSentencesRemainLinked() {
        String source = "若批次缺少識別、封條破損或受到污染。這些情況均須隔離。";
        String question = "哪些情況需要隔離批次？";

        assertSupported(question, source, 0.9);
        assertThat(validator.supportedExcerpt(question, source))
                .contains("缺少識別", "封條破損", "受到污染", "這些情況均須隔離");
    }

    @Test
    void lineWrappedConditionalEnumerationRemainsComplete() {
        String source = "若發生批次識別缺失、封條破損、\n受到污染或物料不符，\n應隔離該批。";
        String excerpt = validator.supportedExcerpt("哪些情況需要隔離批次？", source);

        assertThat(excerpt).contains("批次識別缺失", "封條破損", "受到污染", "物料不符", "應隔離該批");
    }

    @Test
    void quarantineActionWithoutConditionsIsInsufficientForWhichQuestion() {
        String source = "異常批次須隔離。";
        String question = "哪些異常需要隔離？";
        RetrievedChunk result = chunk(source, 0.9);

        assertThat(validator.isSemanticallySufficient(question, List.of(result), 0.45)).isFalse();
        assertThat(validator.supportedExcerpt(question, source)).isEmpty();
    }

    @Test
    void questionHintAloneIsNotAQuarantineFact() {
        String source = "問答：哪些情況需要隔離？";
        String question = "哪些情況需要隔離？";
        RetrievedChunk result = chunk(source, 0.9);

        assertThat(validator.isSemanticallySufficient(question, List.of(result), 0.45)).isFalse();
        assertThat(validator.supportedExcerpt(question, source)).isEmpty();
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
