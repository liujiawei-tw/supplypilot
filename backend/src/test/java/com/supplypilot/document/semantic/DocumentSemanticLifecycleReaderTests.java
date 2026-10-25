package com.supplypilot.document.semantic;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentSemanticLifecycleReaderTests {

    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final SemanticProperties PROPERTIES = new SemanticProperties(
            "jdbc:postgresql://localhost/test", "user", "password", "embedding-model", 3,
            "model-generation-v1", true, 5, 0.5, Duration.ofSeconds(2));

    @Test
    void pgvectorOutageAfterSnapshotCommitShortCircuitsRemainingDocumentsAsUnavailable() {
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        SemanticIndexJobRepository jobs = mock(SemanticIndexJobRepository.class);
        SemanticStore store = mock(SemanticStore.class);
        OutboundDocumentPolicy policy = mock(OutboundDocumentPolicy.class);
        DocumentMetadataRepository documents = mock(DocumentMetadataRepository.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        List<String> events = new java.util.ArrayList<>();
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        doAnswer(invocation -> {
            events.add("mysql-commit");
            return null;
        }).when(transactions).commit(any());

        DocumentMetadata first = approvedDocument(1L, "Forklift capacity is 500 kilograms.");
        DocumentMetadata second = approvedDocument(2L, "Quality checks happen daily.");
        for (DocumentMetadata document : List.of(first, second)) {
            when(documents.findForSemanticRead(document.getId())).thenReturn(Optional.of(document));
            List<DocumentChunk> source = List.of(DocumentChunk.create(document, 0, 0,
                    document.getTitle().length(), document.getTitle(), "[0.0,0.0,0.0]", NOW));
            when(chunks.findByDocumentIdOrderByChunkIndex(document.getId())).thenReturn(source);
            String hash = SemanticBackfillService.sourceHash(source);
            String approvedText = document.getTitle();
            when(policy.inspect(any(), anyList(), anyString()))
                    .thenReturn(new OutboundDocumentPolicy.Decision("APPROVED", null,
                            List.of(approvedText)));
            long documentId = document.getId();
            String version = document.getVersion();
            long generation = document.getSemanticIndexGeneration();
            when(jobs.findCurrent(documentId, version, hash, PROPERTIES.generationKey(), generation))
                    .thenReturn(Optional.of(succeededJob(documentId, hash, generation)));
        }
        doAnswer(invocation -> {
            events.add("pgvector-query");
            throw new IllegalStateException("database unavailable");
        }).when(store).inspectPublication(anyLong(), anyString(), anyString(), anyString(),
                anyLong(), anyList());

        DocumentSemanticLifecycleReader reader = new DocumentSemanticLifecycleReader(
                chunks, jobs, store, PROPERTIES, policy, documents, transactions);
        var result = reader.readAll(List.of(first, second));

        assertThat(result).extracting(status -> status.status()).containsExactly("UNAVAILABLE", "UNAVAILABLE");
        assertThat(result).extracting(status -> status.failureCode())
                .containsExactly("INDEX_STORE_UNAVAILABLE", "INDEX_STORE_UNAVAILABLE");
        assertThat(events).containsExactly("mysql-commit", "pgvector-query");
        verify(store, times(1)).inspectPublication(anyLong(), anyString(), anyString(), anyString(),
                anyLong(), anyList());
    }

    @Test
    void stalePublicationRemainsPendingAndPublicationValidationRunsAfterMysqlCommit() {
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        SemanticIndexJobRepository jobs = mock(SemanticIndexJobRepository.class);
        SemanticStore store = mock(SemanticStore.class);
        OutboundDocumentPolicy policy = mock(OutboundDocumentPolicy.class);
        DocumentMetadataRepository documents = mock(DocumentMetadataRepository.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        AtomicBoolean mysqlTransactionFinished = new AtomicBoolean();
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        doAnswer(invocation -> {
            mysqlTransactionFinished.set(true);
            return null;
        }).when(transactions).commit(any());

        DocumentMetadata document = approvedDocument(3L, "Supplier quality checks are daily.");
        when(documents.findForSemanticRead(3L)).thenReturn(Optional.of(document));
        List<DocumentChunk> source = List.of(DocumentChunk.create(document, 0, 0,
                document.getTitle().length(), document.getTitle(), "[0.0,0.0,0.0]", NOW));
        when(chunks.findByDocumentIdOrderByChunkIndex(3L)).thenReturn(source);
        String hash = SemanticBackfillService.sourceHash(source);
        String approvedText = document.getTitle();
        when(policy.inspect(any(), anyList(), anyString())).thenReturn(
                new OutboundDocumentPolicy.Decision("APPROVED", null, List.of(approvedText)));
        when(jobs.findCurrent(3L, "v1", hash, PROPERTIES.generationKey(), 7L))
                .thenReturn(Optional.of(succeededJob(3L, hash, 7L)));
        var oldPublication = new SemanticStore.PublicationCheck(
                new SemanticStore.IndexState("READY", hash, 1, null, 1, 6L), false, 1);
        when(store.inspectPublication(3L, "v1", PROPERTIES.generationKey(), hash, 7L,
                List.of(new SemanticStore.SourceChunk(0, 0, document.getTitle().length(), document.getTitle()))))
                .thenAnswer(invocation -> {
                    assertThat(mysqlTransactionFinished).isTrue();
                    return oldPublication;
                });

        DocumentSemanticLifecycleReader reader = new DocumentSemanticLifecycleReader(
                chunks, jobs, store, PROPERTIES, policy, documents, transactions);
        var result = reader.read(document);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.failureCode()).isEqualTo("PUBLISHED_INDEX_STALE");
        assertThat(result.publicationGeneration()).isEqualTo(6L);
    }

    private static DocumentMetadata approvedDocument(long id, String source) {
        DocumentMetadata document = mock(DocumentMetadata.class);
        when(document.getId()).thenReturn(id);
        when(document.getStatus()).thenReturn(DocumentStatus.READY);
        when(document.getVersion()).thenReturn("v1");
        when(document.getSemanticReviewStatus()).thenReturn(SemanticReviewStatus.APPROVED);
        when(document.getSemanticApprovedSourceHash()).thenReturn(
                SemanticBackfillService.sourceHash(List.of(DocumentChunk.create(document, 0, 0,
                        source.length(), source, "[0.0,0.0,0.0]", NOW))));
        when(document.getSemanticReviewVersion()).thenReturn("v1");
        when(document.getSemanticIndexGeneration()).thenReturn(7L);
        when(document.getTitle()).thenReturn(source);
        return document;
    }

    private static SemanticIndexJob succeededJob(long documentId, String sourceHash, long generation) {
        return new SemanticIndexJob(100L + documentId, documentId, "v1", sourceHash,
                PROPERTIES.generationKey(), generation, SemanticIndexJobStatus.SUCCEEDED,
                1, 0, null, null, null, NOW, NOW);
    }
}
