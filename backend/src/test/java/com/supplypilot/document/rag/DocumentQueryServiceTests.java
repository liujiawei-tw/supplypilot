package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.dto.response.DocumentQueryStatus;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.semantic.SemanticRetrievalService;
import org.junit.jupiter.api.Test;

class DocumentQueryServiceTests {
    private static final String QUESTION = "SUP-001 的 MAT-001 標準交期是多久？";

    @Test
    void semanticScoreIsOnlyCandidateRankingAndIrrelevantCandidatesAreDropped() {
        SemanticRetrievalService semantic = mock(SemanticRetrievalService.class);
        AnswerProvider answers = mock(AnswerProvider.class);
        RetrievedChunk unrelated = chunk("SUP-001 的報價自開立日起 30 個曆日內有效。", 0.96, 1L);
        RetrievedChunk supporting = chunk("前言😀 English 中文混合。\nSUP-001 supplies MAT-001\n"
                + "MAT-001 標準交期為 ORDERED 後 5 個曆日。\n文件尾端。", 0.72, 2L);
        when(semantic.retrieve(QUESTION)).thenReturn(List.of(unrelated, supporting));
        when(answers.answer(eq(QUESTION), anyList())).thenReturn(
                "MAT-001 標準交期為 ORDERED 後 5 個曆日。");
        DocumentQueryService service = new DocumentQueryService(null, new EvidenceValidator(), answers,
                semantic, properties(true));

        var result = service.query(QUESTION);

        assertThat(result.status()).isEqualTo(DocumentQueryStatus.ANSWERED);
        assertThat(result.answer()).contains("5 個曆日").doesNotContain("30 個曆日", "前言", "文件尾端");
        assertThat(result.sources()).singleElement().satisfies(source ->
                assertThat(source.documentId()).isEqualTo(2L));
        assertThat(result.sources().getFirst().startOffset()).isZero();
        assertThat(result.sources().getFirst().endOffset()).isEqualTo(supporting.chunk().getContent().length());
        assertThat(result.sources().getFirst().excerpt()).isEqualTo(supporting.chunk().getContent());
        verify(answers).answer(eq(QUESTION), org.mockito.ArgumentMatchers.argThat(chunks ->
                chunks.size() == 1 && chunks.getFirst() == supporting));
    }

    @Test
    void highSimilarityWithoutTopicalSupportReturnsInsufficientEvidence() {
        SemanticRetrievalService semantic = mock(SemanticRetrievalService.class);
        AnswerProvider answers = mock(AnswerProvider.class);
        RetrievedChunk unrelated = chunk("SUP-001 的報價自開立日起 30 個曆日內有效。", 0.99, 1L);
        when(semantic.retrieve("這些文件是否規定冷凍海運的保險費率？")).thenReturn(List.of(unrelated));
        DocumentQueryService service = new DocumentQueryService(null, new EvidenceValidator(), answers,
                semantic, properties(true));

        var result = service.query("這些文件是否規定冷凍海運的保險費率？");

        assertThat(result.status()).isEqualTo(DocumentQueryStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.answer()).isNull();
        assertThat(result.sources()).isEmpty();
        verify(answers, never()).answer(eq("這些文件是否規定冷凍海運的保險費率？"), anyList());
    }

    @Test
    void conditionListQuestionWithoutConditionEvidenceReturnsInsufficientEvidence() {
        assertInsufficientConditionList("哪些異常需要隔離？", "異常批次須隔離。");
        assertInsufficientConditionList("哪些情況需要隔離？", "問答：哪些情況需要隔離？");
        assertInsufficientConditionList("哪些情況需要隔離批次？", "依照品質管理規定，異常批次須隔離。");
        assertInsufficientConditionList("哪些異常需要隔離？", "倉庫人員，應依標準程序隔離異常批次。");
        assertInsufficientConditionList("哪些情況需要隔離批次？",
                "問答：哪些情況需要隔離批次？依照規定，異常批次須隔離。");
    }

    @Test
    void independentConditionalClauseReturnsOnlyTheClauseThatSupportsQuarantine() {
        assertConditionAnswer("哪些情況需要隔離批次？",
                "若設備停機，立即通知維修；若封條破損，須隔離批次。",
                "封條破損", "設備停機");
        assertConditionAnswer("哪些情況需要隔離批次？",
                "若設備停機，立即通知維修。若封條破損，須隔離批次。",
                "封條破損", "設備停機");
        assertConditionAnswer("When should the batch be quarantined?",
                "If the machine stops, notify maintenance; if the seal is broken, quarantine the batch.",
                "seal is broken", "machine stops");
    }

    @Test
    void realQualitySourceReturnsCompleteAnswerAndKeepsFullChunkCitation() throws IOException {
        String question = "品質要求中哪些狀況需要隔離批次？";
        String sourceText = Files.readString(Path.of("..", "samples", "documents", "quality-requirement-v1.txt"));
        SemanticRetrievalService semantic = mock(SemanticRetrievalService.class);
        RetrievedChunk source = chunk(sourceText, 0.92, 5L);
        when(semantic.retrieve(question)).thenReturn(List.of(source));
        DocumentQueryService service = new DocumentQueryService(null, new EvidenceValidator(),
                new ExtractiveAnswerProvider(new EvidenceValidator()), semantic, properties(true));

        var result = service.query(question);

        assertThat(result.status()).isEqualTo(DocumentQueryStatus.ANSWERED);
        assertThat(result.answer()).contains("缺少批次識別", "封條破損", "受到污染", "物料不符", "隔離")
                .doesNotContain("問答：", "預期依據：");
        assertThat(result.sources()).singleElement().satisfies(citation -> {
            assertThat(citation.documentId()).isEqualTo(5L);
            assertThat(citation.version()).isEqualTo("1.0");
            assertThat(citation.chunkIndex()).isZero();
            assertThat(citation.startOffset()).isZero();
            assertThat(citation.endOffset()).isEqualTo(sourceText.length());
            assertThat(citation.excerpt()).isEqualTo(sourceText);
        });
    }

    private void assertInsufficientConditionList(String question, String content) {
        SemanticRetrievalService semantic = mock(SemanticRetrievalService.class);
        AnswerProvider answers = mock(AnswerProvider.class);
        RetrievedChunk retrieved = chunk(content, 0.99, 3L);
        when(semantic.retrieve(question)).thenReturn(List.of(retrieved));
        DocumentQueryService service = new DocumentQueryService(null, new EvidenceValidator(), answers,
                semantic, properties(true));

        var result = service.query(question);

        assertThat(result.status()).isEqualTo(DocumentQueryStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.answer()).isNull();
        assertThat(result.sources()).isEmpty();
        verify(answers, never()).answer(eq(question), anyList());
    }

    private void assertConditionAnswer(String question, String content, String expected, String unrelated) {
        SemanticRetrievalService semantic = mock(SemanticRetrievalService.class);
        AnswerProvider answers = mock(AnswerProvider.class);
        RetrievedChunk retrieved = chunk(content, 0.97, 4L);
        when(semantic.retrieve(question)).thenReturn(List.of(retrieved));
        when(answers.answer(eq(question), anyList())).thenAnswer(invocation ->
                new ExtractiveAnswerProvider(new EvidenceValidator()).answer(
                        question, invocation.getArgument(1)));
        DocumentQueryService service = new DocumentQueryService(null, new EvidenceValidator(), answers,
                semantic, properties(true));

        var result = service.query(question);

        assertThat(result.status()).isEqualTo(DocumentQueryStatus.ANSWERED);
        assertThat(result.answer()).contains(expected).doesNotContain(unrelated);
        assertThat(result.sources()).singleElement().satisfies(source ->
                assertThat(source.excerpt()).isEqualTo(content));
    }

    private SemanticProperties properties(boolean readEnabled) {
        return new SemanticProperties("jdbc:postgresql://localhost/test", "test", "test", "model",
                768, "generation", readEnabled, 5, 0.45, Duration.ofSeconds(2));
    }

    private RetrievedChunk chunk(String content, double score, long id) {
        DocumentMetadata document = mock(DocumentMetadata.class);
        when(document.getId()).thenReturn(id);
        when(document.getDocumentType()).thenReturn(DocumentType.SUPPLIER_CONTRACT);
        when(document.getTitle()).thenReturn("Fictional evidence");
        when(document.getVersion()).thenReturn("1.0");
        DocumentChunk chunk = mock(DocumentChunk.class);
        when(chunk.getContent()).thenReturn(content);
        when(chunk.getDocument()).thenReturn(document);
        when(chunk.getChunkIndex()).thenReturn(0);
        when(chunk.getStartOffset()).thenReturn(0);
        when(chunk.getEndOffset()).thenReturn(content.length());
        return new RetrievedChunk(chunk, score);
    }
}
