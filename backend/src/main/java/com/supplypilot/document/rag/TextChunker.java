package com.supplypilot.document.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class TextChunker {

    private static final int CHUNK_SIZE = 900;
    private static final int OVERLAP = 120;

    public List<TextChunk> chunk(String text) {
        List<TextChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int proposedEnd = Math.min(text.length(), start + CHUNK_SIZE);
            int end = boundary(text, start, proposedEnd);
            String content = text.substring(start, end).trim();
            if (!content.isBlank()) {
                chunks.add(new TextChunk(index++, start, end, content));
            }
            if (end == text.length()) {
                break;
            }
            start = Math.max(start + 1, end - OVERLAP);
        }
        return List.copyOf(chunks);
    }

    private int boundary(String text, int start, int proposedEnd) {
        if (proposedEnd == text.length()) {
            return proposedEnd;
        }
        for (int index = proposedEnd; index > start + CHUNK_SIZE / 2; index--) {
            char character = text.charAt(index - 1);
            if (character == '\n' || character == '.' || character == '。' || character == ';' || character == '；') {
                return index;
            }
        }
        return proposedEnd;
    }

    public record TextChunk(int index, int startOffset, int endOffset, String content) {
    }
}
