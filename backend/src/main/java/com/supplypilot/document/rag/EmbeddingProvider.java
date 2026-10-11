package com.supplypilot.document.rag;

import java.util.List;

public interface EmbeddingProvider {
    List<Double> embed(String text);
}
