package com.supplypilot.document.rag;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small normalization helper for deterministic evidence matching; it does not create embeddings. */
final class EvidenceTextTokenizer {
    private EvidenceTextTokenizer() { }

    static List<String> tokens(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                word.append(character);
                if (character >= 0x4E00 && character <= 0x9FFF) {
                    if (word.length() > 1) word.delete(0, word.length() - 1);
                    result.add(word.toString());
                    word.setLength(0);
                }
            } else if (!word.isEmpty()) {
                result.add(word.toString());
                word.setLength(0);
            }
        }
        if (!word.isEmpty()) result.add(word.toString());
        return result;
    }
}
