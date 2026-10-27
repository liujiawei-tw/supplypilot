package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.mapper.DocumentMapper;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocumentIndexPersistenceServiceTests {
    @Test
    void stampsReplacedChunksWithTheCurrentDocumentVersion() {
        DocumentMetadataRepository documents = mock(DocumentMetadataRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        DocumentMetadata document = mock(DocumentMetadata.class);
        when(documents.findByIdForUpdate(7L)).thenReturn(Optional.of(document));
        when(document.getStatus()).thenReturn(DocumentStatus.PROCESSING);
        when(document.getVersion()).thenReturn("v7");
        when(documents.save(document)).thenReturn(document);
        EntityManager entityManager = mock(EntityManager.class);
        DocumentIndexPersistenceService service = new DocumentIndexPersistenceService(
                documents, chunks, Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC),
                mock(DocumentMapper.class), entityManager);

        service.replaceIndex(7L, List.of(new PreparedChunk(0, 0, 12, "quotation text", "embedding")));

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<DocumentChunk>> saved = ArgumentCaptor.forClass((Class) List.class);
        verify(chunks).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement()
                .extracting(DocumentChunk::getDocumentVersion).isEqualTo("v7");
    }
}
