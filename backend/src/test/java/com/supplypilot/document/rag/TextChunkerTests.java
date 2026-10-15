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

    @Test
    void keepsChineseMixedTextAndUtf16SourceOffsetsAligned() {
        String text = "標題：SUP-001 交期為五個曆日。\n"
                + "English query compatibility sample 😀 中文混合內容。\n"
                + "補充說明：Unicode offsets use Java UTF-16 string indices.";

        var chunks = new TextChunker().chunk(text);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).extracting(TextChunker.TextChunk::index).containsExactly(0);
        assertThat(chunks.getFirst().content()).contains("五個曆日", "English", "😀", "中文");
        assertThat(text.substring(chunks.getFirst().startOffset(), chunks.getFirst().endOffset()).trim())
                .isEqualTo(chunks.getFirst().content());
        assertThat(chunks.getFirst().endOffset()).isEqualTo(text.length());
    }
}
