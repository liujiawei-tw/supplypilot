package com.supplypilot.document.rag;

import java.util.List;

public interface AnswerProvider {
    String answer(String question, List<RetrievedChunk> evidence);
}
