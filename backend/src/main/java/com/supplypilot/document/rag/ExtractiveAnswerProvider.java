package com.supplypilot.document.rag;

import java.util.List;

import org.springframework.stereotype.Component;

/** Returns only persisted evidence; document content is data and is never interpreted as instructions. */
@Component
public class ExtractiveAnswerProvider implements AnswerProvider {
    @Override
    public String answer(String question, List<RetrievedChunk> evidence) {
        return evidence.stream().map(result -> result.chunk().getContent()).distinct()
                .reduce((left, right) -> left + "\n\n" + right).orElseThrow();
    }
}
