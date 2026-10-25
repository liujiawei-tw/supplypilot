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
        String content = "SUP-001 standard delivery takes five business days.";
        when(chunk.getContent()).thenReturn(content);
        when(chunk.getEmbedding()).thenReturn("1.0,0.0");
        when(chunk.getChunkIndex()).thenReturn(0);
        when(chunk.getStartOffset()).thenReturn(0);
        when(chunk.getEndOffset()).thenReturn(content.length());
        when(chunk.getCreatedAt()).thenReturn(Instant.EPOCH);
        when(chunks.findRetrievable(DocumentStatus.READY)).thenReturn(List.of(chunk));
        when(embeddings.embed("SUP-001 standard delivery")).thenReturn(List.of(1.0, 0.0));
        String sourceHash = SemanticBackfillService.sourceHash(List.of(chunk));
        when(document.getSemanticApprovedSourceHash()).thenReturn(sourceHash);
        when(document.getSemanticReviewVersion()).thenReturn("v1");
        when(document.getSemanticApprovedBy()).thenReturn(1L);
        when(document.getSemanticApprovedAt()).thenReturn(Instant.EPOCH);
        when(document.getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.APPROVED);
        when(document.getSemanticReviewActorType()).thenReturn("USER");
        when(document.getSemanticReviewActor()).thenReturn("1");
        when(document.getSemanticReviewTrigger()).thenReturn("ADMIN_OUTBOUND_REVIEW");
        when(document.getSemanticReviewPolicyDecision()).thenReturn("APPROVED");

        DocumentRetrievalService retrieval = new DocumentRetrievalService(
                chunks, embeddings, new OutboundDocumentPolicy());

        List<RetrievedChunk> result = retrieval.retrieve("SUP-001 standard delivery");

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.chunk().getContent()).isEqualTo(chunk.getContent());
            assertThat(candidate.score()).isPositive();
        });
        verify(embeddings).embed("SUP-001 standard delivery");
        verifyNoMoreInteractions(embeddings);
    }

    @Test
    void metadataSupplierMatchDoesNotPromoteUnrelatedChunksAheadOfRelevantEighthChunk() {
        String question = "SUP-001 MAT-001 standard delivery time?";
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
        DocumentMetadata document = approvedDocument(17L, "SUP-001");
        List<DocumentChunk> source = new java.util.ArrayList<>();
        for (int index = 0; index < 10; index++) {
            String content = index == 7
                    ? "SUP-001 MAT-001 standard delivery time is 5 business days after order."
                    : "Appendix section " + index + " discusses unrelated office administration.";
            String vector = index == 7 ? "1.0,0.0" : "0.0,1.0";
            source.add(chunk(document, index, content, vector));
        }
        approveDocumentFor(document, source);
        when(chunks.findRetrievable(DocumentStatus.READY)).thenReturn(source);
        when(embeddings.embed(question)).thenReturn(List.of(1.0, 0.0));

        DocumentRetrievalService retrieval = new DocumentRetrievalService(
                chunks, embeddings, new OutboundDocumentPolicy());

        List<RetrievedChunk> results = retrieval.retrieve(question);

        assertThat(results).singleElement().satisfies(candidate -> {
            assertThat(candidate.chunk().getChunkIndex()).isEqualTo(7);
            assertThat(candidate.chunk().getContent()).contains("standard delivery time is 5 business days");
        });
        EvidenceValidator validator = new EvidenceValidator();
        assertThat(validator.isSemanticallySufficient(question, results, 0.15)).isTrue();
        assertThat(validator.supportingEvidence(question, results, 0.15))
                .extracting(result -> result.chunk().getChunkIndex()).containsExactly(7);
    }

    @Test
    void exactSupplierMetadataCanRecallDocumentButCannotSubstituteForSourceEvidence() {
        String question = "SUP-001 standard delivery time?";
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
        DocumentMetadata document = approvedDocument(20L, "SUP-001");
        String content = "Standard delivery time is 5 business days.";
        List<DocumentChunk> source = List.of(chunk(document, 0, content, "1.0,0.0"));
        approveDocumentFor(document, source);
        when(chunks.findRetrievable(DocumentStatus.READY)).thenReturn(source);
        when(embeddings.embed(question)).thenReturn(List.of(1.0, 0.0));

        DocumentRetrievalService retrieval = new DocumentRetrievalService(
                chunks, embeddings, new OutboundDocumentPolicy());

        List<RetrievedChunk> candidates = retrieval.retrieve(question);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.chunk().getContent()).isEqualTo(content);
            assertThat(candidate.score()).isEqualTo(1.0);
        });
        assertThat(new EvidenceValidator().isSemanticallySufficient(question, candidates, 0.15)).isFalse();
    }

    @Test
    void longerSupplierAndMaterialIdentifiersDoNotMatchShorterQueryIdentifiers() {
        String question = "SUP-001 MAT-001 standard delivery time?";
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
        DocumentMetadata document = approvedDocument(18L, "SUP-0012");
        String content = "MAT-001 standard delivery time is 7 business days.";
        DocumentChunk chunk = chunk(document, 0, content, "0.0,1.0");
        List<DocumentChunk> source = List.of(chunk);
        approveDocumentFor(document, source);
        when(chunks.findRetrievable(DocumentStatus.READY)).thenReturn(source);
        when(embeddings.embed(question)).thenReturn(List.of(1.0, 0.0));

        DocumentRetrievalService retrieval = new DocumentRetrievalService(
                chunks, embeddings, new OutboundDocumentPolicy());

        assertThat(retrieval.retrieve(question)).isEmpty();
    }

    @Test
    void longerMaterialIdentifierDoesNotMatchShorterQueryIdentifier() {
        String question = "SUP-001 MAT-001 standard delivery time?";
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
        DocumentMetadata document = approvedDocument(19L, "SUP-001");
        String content = "MAT-0012 standard delivery time is 7 business days.";
        List<DocumentChunk> source = List.of(chunk(document, 0, content, "0.0,1.0"));
        approveDocumentFor(document, source);
        when(chunks.findRetrievable(DocumentStatus.READY)).thenReturn(source);
        when(embeddings.embed(question)).thenReturn(List.of(1.0, 0.0));

        DocumentRetrievalService retrieval = new DocumentRetrievalService(
                chunks, embeddings, new OutboundDocumentPolicy());

        assertThat(retrieval.retrieve(question)).isEmpty();
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

    private DocumentMetadata approvedDocument(Long id, String supplierCode) {
        DocumentMetadata document = mock(DocumentMetadata.class);
        Supplier supplier = mock(Supplier.class);
        when(supplier.getCode()).thenReturn(supplierCode);
        when(supplier.getName()).thenReturn("Acme Components");
        when(document.getId()).thenReturn(id);
        when(document.getStatus()).thenReturn(DocumentStatus.READY);
        when(document.getDocumentType()).thenReturn(DocumentType.SUPPLIER_CONTRACT);
        when(document.getSupplier()).thenReturn(supplier);
        when(document.getTitle()).thenReturn("Supplier delivery procedure");
        when(document.getVersion()).thenReturn("v1");
        when(document.getSemanticReviewVersion()).thenReturn("v1");
        when(document.getSemanticApprovedBy()).thenReturn(1L);
        when(document.getSemanticApprovedAt()).thenReturn(Instant.EPOCH);
        when(document.getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.APPROVED);
        when(document.getSemanticReviewActorType()).thenReturn("USER");
        when(document.getSemanticReviewActor()).thenReturn("1");
        when(document.getSemanticReviewTrigger()).thenReturn("ADMIN_OUTBOUND_REVIEW");
        when(document.getSemanticReviewPolicyDecision()).thenReturn("APPROVED");
        return document;
    }

    private DocumentChunk chunk(DocumentMetadata document, int index, String content, String embedding) {
        DocumentChunk chunk = mock(DocumentChunk.class);
        when(chunk.getDocument()).thenReturn(document);
        when(chunk.getChunkIndex()).thenReturn(index);
        when(chunk.getStartOffset()).thenReturn(index * 100);
        when(chunk.getEndOffset()).thenReturn(index * 100 + content.length());
        when(chunk.getContent()).thenReturn(content);
        when(chunk.getEmbedding()).thenReturn(embedding);
        when(chunk.getCreatedAt()).thenReturn(Instant.EPOCH);
        return chunk;
    }

    private void approveDocumentFor(DocumentMetadata document, List<DocumentChunk> source) {
        String sourceHash = SemanticBackfillService.sourceHash(source);
        when(document.getSemanticApprovedSourceHash()).thenReturn(sourceHash);
    }
}
