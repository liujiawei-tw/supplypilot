package com.supplypilot.document.rag;

import java.util.List;

import org.springframework.stereotype.Component;

/** Request-local evidence capture shared by the Advisor post-processor and response mapper. */
@Component
public class RagEvidenceCollector {
    private final ThreadLocal<List<VerifiedRagEvidence>> current = new ThreadLocal<>();

    public void begin() {
        if (current.get() != null) throw new IllegalStateException("RAG evidence context is already active");
        current.set(List.of());
    }

    public void set(List<VerifiedRagEvidence> evidence) {
        if (current.get() == null) throw new IllegalStateException("RAG evidence context is not active");
        current.set(List.copyOf(evidence));
    }

    public List<VerifiedRagEvidence> get() {
        List<VerifiedRagEvidence> evidence = current.get();
        if (evidence == null) throw new IllegalStateException("RAG evidence context is not active");
        return evidence;
    }

    public void clear() {
        current.remove();
    }
}
