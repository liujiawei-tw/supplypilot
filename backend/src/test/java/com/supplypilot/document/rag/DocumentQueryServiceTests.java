package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        RetrievedChunk supporting = chunk("SUP-001 supplies MAT-001\n"
                + "MAT-001 標準交期為 ORDERED 後 5 個曆日。", 0.72, 2L);
        when(semantic.retrieve(QUESTION)).thenReturn(List.of(unrelated, supporting));
        when(answers.answer(eq(QUESTION), anyList())).thenReturn(
                "MAT-001 標準交期為 ORDERED 後 5 個曆日。");
        DocumentQueryService service = new DocumentQueryService(null, new EvidenceValidator(), answers,
                semantic, properties(true));

        var result = service.query(QUESTION);

        assertThat(result.status()).isEqualTo(DocumentQueryStatus.ANSWERED);
        assertThat(result.answer()).contains("5 個曆日").doesNotContain("30 個曆日");
        assertThat(result.sources()).singleElement().satisfies(source ->
                assertThat(source.documentId()).isEqualTo(2L));
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
