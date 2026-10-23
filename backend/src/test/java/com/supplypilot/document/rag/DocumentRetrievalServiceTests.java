package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.time.Instant;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.SemanticBackfillService;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.supplier.domain.Supplier;
import org.junit.jupiter.api.Test;

class DocumentRetrievalServiceTests {

    @Test
    void exactSupplierIdentifierCanFindMySqlChunkThroughDocumentMetadata() {
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
        DocumentMetadata document = mock(DocumentMetadata.class);
        Supplier supplier = mock(Supplier.class);
        DocumentChunk chunk = mock(DocumentChunk.class);

        when(supplier.getCode()).thenReturn("SUP-001");
        when(supplier.getName()).thenReturn("Acme Components");
        when(document.getId()).thenReturn(7L);
        when(document.getStatus()).thenReturn(DocumentStatus.READY);
        when(document.getDocumentType()).thenReturn(DocumentType.SUPPLIER_CONTRACT);
        when(document.getSupplier()).thenReturn(supplier);
        when(document.getTitle()).thenReturn("Supplier delivery procedure");
        when(document.getVersion()).thenReturn("v1");
        when(chunk.getDocument()).thenReturn(document);
        String content = "Standard delivery takes five business days.";
        when(chunk.getContent()).thenReturn(content);
        when(chunk.getEmbedding()).thenReturn("0.0,1.0");
        when(chunk.getChunkIndex()).thenReturn(0);
        when(chunk.getStartOffset()).thenReturn(0);
        when(chunk.getEndOffset()).thenReturn(content.length());
        when(chunk.getCreatedAt()).thenReturn(Instant.EPOCH);
        when(chunks.findRetrievable(DocumentStatus.READY)).thenReturn(List.of(chunk));
        when(embeddings.embed("SUP-001")).thenReturn(List.of(1.0, 0.0));
        String sourceHash = SemanticBackfillService.sourceHash(List.of(chunk));
        when(document.getSemanticApprovedSourceHash()).thenReturn(sourceHash);
        when(document.getSemanticReviewVersion()).thenReturn("v1");
        when(document.getSemanticApprovedBy()).thenReturn(1L);
        when(document.getSemanticApprovedAt()).thenReturn(Instant.EPOCH);
        when(document.getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.APPROVED);

        DocumentRetrievalService retrieval = new DocumentRetrievalService(
                chunks, embeddings, new OutboundDocumentPolicy());

        List<RetrievedChunk> result = retrieval.retrieve("SUP-001");

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.chunk().getContent()).isEqualTo(chunk.getContent());
            assertThat(candidate.score()).isPositive();
        });
        verify(embeddings).embed("SUP-001");
        verifyNoMoreInteractions(embeddings);
    }

    @Test
    void skippedUnreviewedStaleAndInactiveDocumentCannotEnterMySqlEvidenceResults() {
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
        DocumentMetadata document = mock(DocumentMetadata.class);
        DocumentChunk chunk = mock(DocumentChunk.class);

        when(document.getId()).thenReturn(8L);
        when(document.getStatus()).thenReturn(DocumentStatus.READY);
        when(document.getDocumentType()).thenReturn(DocumentType.SUPPLIER_CONTRACT);
        when(document.getTitle()).thenReturn("Supplier procedure");
        when(document.getVersion()).thenReturn("v1");
        when(chunk.getDocument()).thenReturn(document);
        String content = "Forklift maintenance occurs each month.";
        when(chunk.getContent()).thenReturn(content);
        when(chunk.getEmbedding()).thenReturn("1.0,0.0");
        when(chunk.getChunkIndex()).thenReturn(0);
        when(chunk.getStartOffset()).thenReturn(0);
        when(chunk.getEndOffset()).thenReturn(content.length());
        when(chunk.getCreatedAt()).thenReturn(Instant.EPOCH);
        when(chunks.findRetrievable(DocumentStatus.READY)).thenReturn(List.of(chunk));
        when(embeddings.embed("forklift maintenance")).thenReturn(List.of(1.0, 0.0));
        String sourceHash = SemanticBackfillService.sourceHash(List.of(chunk));
        when(document.getSemanticApprovedSourceHash()).thenReturn(sourceHash);
        when(document.getSemanticReviewVersion()).thenReturn("v1");
        when(document.getSemanticApprovedBy()).thenReturn(1L);
        when(document.getSemanticApprovedAt()).thenReturn(Instant.EPOCH);
        when(document.getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.SKIPPED);
        when(document.getSemanticReviewReason()).thenReturn("ADMIN_EXCLUDED");

        DocumentRetrievalService retrieval = new DocumentRetrievalService(
                chunks, embeddings, new OutboundDocumentPolicy());

        assertThat(retrieval.retrieve("forklift maintenance")).isEmpty();

        when(document.getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.REQUIRES_REVIEW);
        assertThat(retrieval.retrieve("forklift maintenance")).isEmpty();

        when(document.getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.APPROVED);
        when(document.getSemanticReviewVersion()).thenReturn("old-version");
        assertThat(retrieval.retrieve("forklift maintenance")).isEmpty();

        when(document.getSemanticReviewVersion()).thenReturn("v1");
        when(document.getStatus()).thenReturn(DocumentStatus.INACTIVE);
        assertThat(retrieval.retrieve("forklift maintenance")).isEmpty();
    }
}
