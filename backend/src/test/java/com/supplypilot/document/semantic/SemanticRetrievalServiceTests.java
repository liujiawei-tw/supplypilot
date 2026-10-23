package com.supplypilot.document.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.rag.RetrievedChunk;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class SemanticRetrievalServiceTests {
    private static final long DOCUMENT_ID = 41L;
    private static final String QUESTION = "SUP-001 標準交期";
    private static final String VERSION = "v2";
    private static final String CONTENT = "SUP-001 標準交期為 ORDERED 後 5 個曆日。";
    private static final List<Float> QUERY_VECTOR = List.of(1.0f, 0.0f);

    @Test
    void currentApprovedSemanticCandidateReturnsTheVerifiedSourceChunk() {
        Fixture fixture = fixture(VERSION, DocumentStatus.READY);

        List<RetrievedChunk> result = fixture.service.retrieve(QUESTION);

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.chunk().getDocument().getId()).isEqualTo(DOCUMENT_ID);
            assertThat(candidate.chunk().getDocument().getVersion()).isEqualTo(VERSION);
            assertThat(candidate.chunk().getContent()).isEqualTo(CONTENT);
            assertThat(candidate.chunk().getStartOffset()).isZero();
            assertThat(candidate.chunk().getEndOffset()).isEqualTo(CONTENT.length());
        });
    }

    @Test
    void staleSemanticVersionCandidateIsDroppedBeforeReadingSourceChunks() {
        Fixture fixture = fixture("v1", DocumentStatus.READY);

        assertThat(fixture.service.retrieve(QUESTION)).isEmpty();

        verify(fixture.chunks, never()).findByDocumentIdOrderByChunkIndex(DOCUMENT_ID);
    }

    @Test
    void inactiveSemanticCandidateIsDroppedBeforeReadingSourceChunks() {
        Fixture fixture = fixture(VERSION, DocumentStatus.INACTIVE);

        assertThat(fixture.service.retrieve(QUESTION)).isEmpty();

        verify(fixture.chunks, never()).findByDocumentIdOrderByChunkIndex(DOCUMENT_ID);
    }

    private Fixture fixture(String candidateVersion, DocumentStatus documentStatus) {
        SemanticStore store = mock(SemanticStore.class);
        SemanticEmbeddingService embeddings = mock(SemanticEmbeddingService.class);
        SemanticReadinessService readiness = mock(SemanticReadinessService.class);
        DocumentMetadataRepository documents = mock(DocumentMetadataRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        DocumentMetadata document = mock(DocumentMetadata.class);
        DocumentChunk source = DocumentChunk.create(document, 0, 0, CONTENT.length(), CONTENT, "", Instant.EPOCH);
        SemanticProperties properties = new SemanticProperties("jdbc:postgresql://localhost/test", "test", "test",
                "model", 2, "generation", true, 5, 0.45, Duration.ofSeconds(2));

        when(document.getId()).thenReturn(DOCUMENT_ID);
        when(document.getVersion()).thenReturn(VERSION);
        when(document.getStatus()).thenReturn(documentStatus);
        when(document.getSemanticReviewVersion()).thenReturn(VERSION);
        when(document.getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.APPROVED);
        when(document.getSemanticApprovedBy()).thenReturn(7L);
        when(document.getSemanticApprovedAt()).thenReturn(Instant.EPOCH);
        when(embeddings.query(QUESTION)).thenReturn(QUERY_VECTOR);
        when(readiness.canRead()).thenReturn(true);
        when(store.search(QUERY_VECTOR, properties.topK() * 5, 0)).thenReturn(List.of(
                new SemanticStore.Candidate(DOCUMENT_ID, candidateVersion, 0, 0, CONTENT.length(), CONTENT, 0.92)));
        when(documents.findForSemanticRead(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(chunks.findByDocumentIdOrderByChunkIndex(DOCUMENT_ID)).thenReturn(List.of(source));
        when(store.state(DOCUMENT_ID, VERSION)).thenAnswer(call -> {
            String hash = SemanticBackfillService.sourceHash(List.of(source));
            return new SemanticStore.IndexState("READY", hash, 1, null, 1);
        });
        when(store.chunkCount(DOCUMENT_ID, VERSION)).thenReturn(1);
        when(document.getSemanticApprovedSourceHash()).thenReturn(SemanticBackfillService.sourceHash(List.of(source)));

        SemanticRetrievalService service = new SemanticRetrievalService(store, embeddings, readiness,
                new OutboundDocumentPolicy(), properties, documents, chunks, new SimpleMeterRegistry());
        return new Fixture(service, chunks);
    }

    private record Fixture(SemanticRetrievalService service, DocumentChunkRepository chunks) { }
}
