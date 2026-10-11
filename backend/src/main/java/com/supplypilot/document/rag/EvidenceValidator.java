package com.supplypilot.document.rag;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class EvidenceValidator {

    private static final double MINIMUM_SCORE = 0.15;

    public boolean isSufficient(String question, List<RetrievedChunk> results) {
        if (results.isEmpty() || results.getFirst().score() < MINIMUM_SCORE) {
            return false;
        }
        Set<String> queryTerms = new HashSet<>(LocalLexicalEmbeddingProvider.tokens(question));
        queryTerms.removeIf(term -> term.length() <= 1 && term.charAt(0) < 0x4E00);
        if (queryTerms.isEmpty()) {
            return false;
        }
        Set<String> evidenceTerms = new HashSet<>();
        results.forEach(result -> evidenceTerms.addAll(LocalLexicalEmbeddingProvider.tokens(result.chunk().getContent())));
        long matches = queryTerms.stream().filter(evidenceTerms::contains).count();
        return matches >= Math.min(2, queryTerms.size());
    }
}
