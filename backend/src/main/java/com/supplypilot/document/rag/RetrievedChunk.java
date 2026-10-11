package com.supplypilot.document.rag;

import com.supplypilot.document.domain.DocumentChunk;

public record RetrievedChunk(DocumentChunk chunk, double score) {
}
