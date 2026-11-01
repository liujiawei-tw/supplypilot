package com.supplypilot.document.semantic;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.supplypilot.demo.DemoCorpusRagEligibility;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.supplier.domain.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiVectorIndexServiceTests {
    private static final long DOCUMENT_ID = 72L;
    private static final long GENERATION = 5L;
    private static final String VERSION = "v3";
    private static final String CONTENT = "SUP-072 standard delivery takes five business days.";

    @Test
    void writesApprovedSourceChunksToPgVectorWithVerifiableLiveMetadata() {
        Fixture fixture = fixture();
        when(fixture.registry().publish(DOCUMENT_ID, VERSION, fixture.sourceHash(), GENERATION,
                List.of(new SemanticStore.SafeChunk(0, 0, CONTENT.length(), CONTENT))))
                .thenReturn(true);

        boolean published = fixture.service().index(fixture.document(), fixture.sourceHash(),
                List.of(new SemanticIndexSnapshotReader.SourceChunk(0, 0, CONTENT.length(), CONTENT)));

        assertThat(published).isTrue();
        @SuppressWarnings({"rawtypes", "unchecked"})
        org.mockito.ArgumentCaptor<List<Document>> indexed = org.mockito.ArgumentCaptor.forClass((Class) List.class);
        verify(fixture.vectorStore()).add(indexed.capture());
        assertThat(indexed.getValue()).singleElement().satisfies(vector -> {
            assertThat(vector.getText()).isEqualTo(CONTENT);
            assertThat(vector.getMetadata())
                    .containsEntry("documentId", DOCUMENT_ID)
                    .containsEntry("documentVersion", VERSION)
                    .containsEntry("generationKey", "gemini-embedding-001-768-spring-ai-token-v1")
                    .containsEntry("documentGeneration", GENERATION)
                    .containsEntry("sourceHash", fixture.sourceHash())
                    .containsEntry("chunkIndex", 0)
                    .containsEntry("startOffset", 0)
                    .containsEntry("endOffset", CONTENT.length())
                    .containsEntry("documentStatus", "READY")
                    .containsEntry("outboundApprovalStatus", "APPROVED")
                    .containsEntry("documentType", "SUPPLIER_CONTRACT")
                    .containsEntry("title", "Supplier agreement")
                    .containsEntry("supplierCode", "SUP-072");
        });
    }

    @Test
    void sourceApprovalLeaseFailureDoesNotAddOrPublishVectors() {
        Fixture fixture = fixture();

        boolean published = fixture.service().index(DOCUMENT_ID, VERSION, fixture.sourceHash(), GENERATION,
                List.of(new SemanticIndexSnapshotReader.SourceChunk(0, 0, CONTENT.length(), CONTENT)), () -> false);

        assertThat(published).isFalse();
        verify(fixture.vectorStore(), never()).add(anyList());
        verify(fixture.registry(), never()).publish(DOCUMENT_ID, VERSION, fixture.sourceHash(), GENERATION,
                List.of(new SemanticStore.SafeChunk(0, 0, CONTENT.length(), CONTENT)));
        verify(fixture.vectorStore(), never()).delete(anyList());
    }

    @Test
    void postWriteLeaseFailureLeavesSharedDeterministicIdsForFencedCleanup() {
        Fixture fixture = fixture();
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();

        boolean published = fixture.service().index(DOCUMENT_ID, VERSION, fixture.sourceHash(), GENERATION,
                List.of(new SemanticIndexSnapshotReader.SourceChunk(0, 0, CONTENT.length(), CONTENT)),
                () -> checks.getAndIncrement() == 0);

        assertThat(published).isFalse();
        verify(fixture.vectorStore()).add(anyList());
        verify(fixture.registry(), never()).publish(DOCUMENT_ID, VERSION, fixture.sourceHash(), GENERATION,
                List.of(new SemanticStore.SafeChunk(0, 0, CONTENT.length(), CONTENT)));
        verify(fixture.vectorStore(), never()).delete(anyList());
    }

    @Test
    void rejectedPublicationLeavesSharedDeterministicIdsForFencedCleanup() {
        Fixture fixture = fixture();
        when(fixture.registry().publish(DOCUMENT_ID, VERSION, fixture.sourceHash(), GENERATION,
                List.of(new SemanticStore.SafeChunk(0, 0, CONTENT.length(), CONTENT))))
                .thenReturn(false);

        boolean published = fixture.service().index(DOCUMENT_ID, VERSION, fixture.sourceHash(), GENERATION,
                List.of(new SemanticIndexSnapshotReader.SourceChunk(0, 0, CONTENT.length(), CONTENT)), () -> true);

        assertThat(published).isFalse();
        verify(fixture.vectorStore()).add(anyList());
        verify(fixture.vectorStore(), never()).delete(anyList());
    }

    @Test
    void olderPublisherCleanupDeletesOnlyRefsBelowLiveGenerationFence() {
        Fixture fixture = fixture();
        when(fixture.document().getSemanticIndexGeneration()).thenReturn(GENERATION + 1);
        when(fixture.registry().publish(DOCUMENT_ID, VERSION, fixture.sourceHash(), GENERATION,
                List.of(new SemanticStore.SafeChunk(0, 0, CONTENT.length(), CONTENT))))
                .thenReturn(true);
        when(fixture.registry().vectorReferencesForDocument(DOCUMENT_ID)).thenReturn(List.of(
                vectorReference("generation-3", 3),
                vectorReference("generation-5", GENERATION),
                vectorReference("generation-6", GENERATION + 1),
                vectorReference("generation-7", GENERATION + 2)));

        boolean published = fixture.service().index(DOCUMENT_ID, VERSION, fixture.sourceHash(), GENERATION,
                List.of(new SemanticIndexSnapshotReader.SourceChunk(0, 0, CONTENT.length(), CONTENT)), () -> true);

        assertThat(published).isTrue();
        verify(fixture.vectorStore()).delete(List.of("generation-3", "generation-5"));
        verify(fixture.vectorStore(), never()).delete(List.of("generation-6", "generation-7"));
    }

    @Test
    void scheduledCleanupUsesGenerationFenceEvenWhenDocumentIsIneligible() {
        Fixture fixture = fixture();
        when(fixture.document().getStatus()).thenReturn(DocumentStatus.INACTIVE);
        when(fixture.document().getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.REQUIRES_REVIEW);
        when(fixture.document().getSemanticIndexGeneration()).thenReturn(GENERATION);
        when(fixture.registry().vectorDocumentIdsAfter(0, 50)).thenReturn(List.of(DOCUMENT_ID));
        when(fixture.registry().vectorReferencesForDocument(DOCUMENT_ID)).thenReturn(List.of(
                vectorReference("generation-3", 3),
                vectorReference("generation-5", GENERATION),
                vectorReference("generation-6", GENERATION + 1)));

        fixture.service().cleanupStaleVectorPage();

        verify(fixture.vectorStore()).delete(List.of("generation-3"));
        verify(fixture.vectorStore(), never()).delete(List.of("generation-5", "generation-6"));
    }

    @Test
    void scheduledCleanupMayDeleteAllRefsWhenDocumentWasRemoved() {
        Fixture fixture = fixture();
        when(fixture.documents().findForSemanticRead(DOCUMENT_ID)).thenReturn(Optional.empty());
        when(fixture.registry().vectorDocumentIdsAfter(0, 50)).thenReturn(List.of(DOCUMENT_ID));
        when(fixture.registry().vectorReferencesForDocument(DOCUMENT_ID)).thenReturn(List.of(
                vectorReference("generation-3", 3),
                vectorReference("generation-5", GENERATION),
                vectorReference("generation-7", GENERATION + 2)));

        fixture.service().cleanupStaleVectorPage();

        verify(fixture.vectorStore()).delete(List.of("generation-3", "generation-5", "generation-7"));
    }

    private Fixture fixture() {
        PgVectorStore vectorStore = mock(PgVectorStore.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ragWriteVectorStore", vectorStore);
        ObjectProvider<PgVectorStore> stores = beans.getBeanProvider(PgVectorStore.class);
        SemanticStore registry = mock(SemanticStore.class);
        SemanticProperties properties = new SemanticProperties("jdbc:postgresql://localhost/test", "test", "test",
                "gemini-embedding-001", 768, "gemini-embedding-001-768-spring-ai-token-v1", true, 5, 0.45,
                java.time.Duration.ofSeconds(2));
        DocumentMetadataRepository documents = mock(DocumentMetadataRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        OutboundDocumentPolicy policy = mock(OutboundDocumentPolicy.class);
        DemoCorpusRagEligibility eligibility = mock(DemoCorpusRagEligibility.class);
        DocumentMetadata document = mock(DocumentMetadata.class);
        Supplier supplier = supplier();
        DocumentChunk chunk = DocumentChunk.create(document, VERSION, 0, 0, CONTENT.length(), CONTENT, "[]", Instant.EPOCH);
        List<DocumentChunk> source = List.of(chunk);
        String sourceHash = SemanticBackfillService.sourceHash(source);

        when(document.getId()).thenReturn(DOCUMENT_ID);
        when(document.getVersion()).thenReturn(VERSION);
        when(document.getStatus()).thenReturn(DocumentStatus.READY);
        when(document.getSemanticIndexGeneration()).thenReturn(GENERATION);
        when(document.getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.APPROVED);
        when(document.getSemanticReviewVersion()).thenReturn(VERSION);
        when(document.getSemanticApprovedSourceHash()).thenReturn(sourceHash);
        when(document.getDocumentType()).thenReturn(DocumentType.SUPPLIER_CONTRACT);
        when(document.getTitle()).thenReturn("Supplier agreement");
        when(document.getSupplier()).thenReturn(supplier);
        when(documents.findForSemanticRead(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(chunks.findByDocumentIdOrderByChunkIndex(DOCUMENT_ID)).thenReturn(source);
        when(eligibility.isEligible(document)).thenReturn(true);
        when(eligibility.inspectSource(document, source, sourceHash, GENERATION, policy))
                .thenReturn(new OutboundDocumentPolicy.Decision("APPROVED", null, List.of(CONTENT)));

        SpringAiVectorIndexService service = new SpringAiVectorIndexService(stores, registry, properties,
                documents, chunks, policy, eligibility);
        return new Fixture(service, registry, vectorStore, documents, document, sourceHash);
    }

    private SemanticStore.VectorReference vectorReference(String id, long generation) {
        return new SemanticStore.VectorReference(id, VERSION,
                "gemini-embedding-001-768-spring-ai-token-v1", "hash-" + generation,
                Long.toString(generation));
    }

    private Supplier supplier() {
        Supplier supplier = mock(Supplier.class);
        when(supplier.getId()).thenReturn(901L);
        when(supplier.getCode()).thenReturn("SUP-072");
        when(supplier.getName()).thenReturn("Test Supplier");
        return supplier;
    }

    private record Fixture(SpringAiVectorIndexService service, SemanticStore registry,
            PgVectorStore vectorStore, DocumentMetadataRepository documents,
            DocumentMetadata document, String sourceHash) { }
}
