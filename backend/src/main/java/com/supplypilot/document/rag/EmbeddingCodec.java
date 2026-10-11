package com.supplypilot.document.rag;

import java.util.Arrays;
import java.util.List;

final class EmbeddingCodec {
    private EmbeddingCodec() {
    }

    static String encode(List<Double> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("Embedding response is invalid");
        }
        return values.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElseThrow();
    }

    static List<Double> decode(String value) {
        return Arrays.stream(value.split(",")).map(Double::parseDouble).toList();
    }
}
