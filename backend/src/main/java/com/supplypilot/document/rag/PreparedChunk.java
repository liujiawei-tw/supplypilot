package com.supplypilot.document.rag;

record PreparedChunk(int index, int startOffset, int endOffset, String content, String embedding) {
}
