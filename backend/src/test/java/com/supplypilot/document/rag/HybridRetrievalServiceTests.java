package com.supplypilot.document.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.semantic.SemanticRetrievalService;
import org.junit.jupiter.api.Test;

class HybridRetrievalServiceTests {

    @Test
    void reciprocalRankFusionCombinesBothSourcesAndDeduplicatesTheSameVersionedChunk() {
        DocumentRetrievalService lexical = mock(DocumentRetrievalService.class);
        SemanticRetrievalService semantic = mock(SemanticRetrievalService.class);
        RetrievedChunk lexicalFirst = chunk(1L, "v1", 0, "forklift capacity is 500 kilograms");
        RetrievedChunk sharedLexical = chunk(2L, "v3", 0, "delivery terms are net 30 days");
        RetrievedChunk sharedSemantic = chunk(2L, "v3", 0, "delivery terms are net 30 days");
        RetrievedChunk semanticOnly = chunk(3L, "v2", 0, "quality inspection is daily");
        when(lexical.retrieve("forklift capacity"))
                .thenReturn(List.of(lexicalFirst, sharedLexical));
        when(semantic.retrieve("forklift capacity"))
                .thenReturn(List.of(sharedSemantic, semanticOnly));

        HybridRetrievalService service = new HybridRetrievalService(lexical, semantic,
                properties(true), new OutboundDocumentPolicy());

        List<RetrievedChunk> result = service.retrieve("forklift capacity");

        assertThat(result).extracting(candidate -> candidate.chunk().getDocument().getId())
                .containsExactly(2L, 1L, 3L);
        assertThat(result.getFirst().score()).isGreaterThan(result.get(1).score());
        assertThat(result).filteredOn(candidate -> candidate.chunk().getDocument().getId().equals(2L))
                .hasSize(1);
    }

    @Test
    void semanticProviderIsNotCalledWhenSemanticReadIsDisabled() {
        DocumentRetrievalService lexical = mock(DocumentRetrievalService.class);
        SemanticRetrievalService semantic = mock(SemanticRetrievalService.class);
        RetrievedChunk lexicalOnly = chunk(4L, "v1", 0, "delivery terms");
        when(lexical.retrieve("delivery terms")).thenReturn(List.of(lexicalOnly));

        HybridRetrievalService service = new HybridRetrievalService(lexical, semantic,
                properties(false), new OutboundDocumentPolicy());

        assertThat(service.retrieve("delivery terms")).hasSize(1);

        verify(semantic, never()).retrieve("delivery terms");
    }

    @Test
    void conflictingChunkContentAcrossSourcesIsFailedClosed() {
        DocumentRetrievalService lexical = mock(DocumentRetrievalService.class);
        SemanticRetrievalService semantic = mock(SemanticRetrievalService.class);
        RetrievedChunk original = chunk(5L, "v1", 0, "original delivery terms");
        RetrievedChunk altered = chunk(5L, "v1", 0, "altered stale terms");
        when(lexical.retrieve("delivery terms")).thenReturn(List.of(original));
        when(semantic.retrieve("delivery terms")).thenReturn(List.of(altered));

        HybridRetrievalService service = new HybridRetrievalService(lexical, semantic,
                properties(true), new OutboundDocumentPolicy());

        assertThat(service.retrieve("delivery terms")).isEmpty();
    }

    private SemanticProperties properties(boolean readEnabled) {
        return new SemanticProperties("jdbc:postgresql://localhost/test", "test", "test", "model",
                768, "generation", readEnabled, 5, 0.45, Duration.ofSeconds(2));
    }

    private RetrievedChunk chunk(Long id, String version, int index, String content) {
        DocumentMetadata document = mock(DocumentMetadata.class);
        when(document.getId()).thenReturn(id);
        when(document.getVersion()).thenReturn(version);
        DocumentChunk chunk = mock(DocumentChunk.class);
        when(chunk.getDocument()).thenReturn(document);
        when(chunk.getChunkIndex()).thenReturn(index);
        when(chunk.getStartOffset()).thenReturn(0);
        when(chunk.getEndOffset()).thenReturn(content.length());
        when(chunk.getContent()).thenReturn(content);
        return new RetrievedChunk(chunk, 0.8);
    }
}
