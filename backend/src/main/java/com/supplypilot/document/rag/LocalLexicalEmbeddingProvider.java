package com.supplypilot.document.rag;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Deployable V1 lexical hashing representation. It is intentionally not described as a semantic model.
 * The provider boundary allows replacement by a managed embedding service without changing ingestion.
 */
@Component
public class LocalLexicalEmbeddingProvider implements EmbeddingProvider {

    static final int DIMENSIONS = 256;

    @Override
    public List<Double> embed(String text) {
        double[] vector = new double[DIMENSIONS];
        for (String token : tokens(text)) {
            int index = Math.floorMod(token.hashCode(), DIMENSIONS);
            vector[index] += 1.0;
        }
        double norm = 0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        List<Double> result = new ArrayList<>(DIMENSIONS);
        for (double value : vector) {
            result.add(norm == 0 ? 0.0 : value / norm);
        }
        return List.copyOf(result);
    }

    static List<String> tokens(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char character = normalized.charAt(i);
            if (Character.isLetterOrDigit(character)) {
                word.append(character);
                if (character >= 0x4E00 && character <= 0x9FFF) {
                    if (word.length() > 1) {
                        word.delete(0, word.length() - 1);
                    }
                    result.add(word.toString());
                    word.setLength(0);
                }
            } else if (!word.isEmpty()) {
                result.add(word.toString());
                word.setLength(0);
            }
        }
        if (!word.isEmpty()) {
            result.add(word.toString());
        }
        return result;
    }
}
