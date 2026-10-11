package com.supplypilot.document.rag;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class EvidenceValidator {

    private static final double MINIMUM_SCORE = 0.15;
    private static final Set<String> QUESTION_WORDS = Set.of(
            "a", "an", "and", "are", "do", "does", "for", "how", "in", "is", "of", "on", "the",
            "to", "what", "when", "where", "which", "who", "why");

    public boolean isSufficient(String question, List<RetrievedChunk> results) {
        if (results.isEmpty() || results.getFirst().score() < MINIMUM_SCORE) {
            return false;
        }
        Set<String> queryTerms = new HashSet<>(LocalLexicalEmbeddingProvider.tokens(question));
        queryTerms.removeIf(term -> QUESTION_WORDS.contains(term)
                || term.length() <= 1 && term.charAt(0) < 0x4E00);
        if (queryTerms.isEmpty()) {
            return false;
        }
        return results.stream().anyMatch(result -> {
            Set<String> evidenceTerms = new HashSet<>(
                    LocalLexicalEmbeddingProvider.tokens(result.chunk().getContent()));
            return evidenceTerms.containsAll(queryTerms);
        });
    }
}
