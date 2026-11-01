package com.supplypilot.document.semantic;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically removes superseded or revoked chunks from the dedicated Spring AI vector table. */
@Component
@ConditionalOnProperty(prefix = "supplypilot.semantic.indexing", name = "worker-enabled",
        havingValue = "true", matchIfMissing = true)
public class SemanticVectorCleanupScheduler {
    private final SpringAiVectorIndexService vectorIndex;

    public SemanticVectorCleanupScheduler(SpringAiVectorIndexService vectorIndex) {
        this.vectorIndex = vectorIndex;
    }

    @Scheduled(fixedDelayString = "${supplypilot.semantic.vector-cleanup-interval-millis:300000}")
    public void reconcile() {
        vectorIndex.cleanupStaleVectorPage();
    }
}
