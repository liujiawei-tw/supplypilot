package com.supplypilot.document.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTests {

    @Test
    void createsOrderedOverlappingChunksWithSourceOffsets() {
        String text = "first sentence. " + "x".repeat(1000) + ". final sentence";
        var chunks = new TextChunker().chunk(text);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(TextChunker.TextChunk::index).containsExactly(0, 1);
        assertThat(chunks.get(1).startOffset()).isLessThan(chunks.get(0).endOffset());
        assertThat(text.substring(chunks.get(0).startOffset(), chunks.get(0).endOffset()).trim())
                .isEqualTo(chunks.get(0).content());
    }
}
