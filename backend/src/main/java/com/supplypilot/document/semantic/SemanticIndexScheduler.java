package com.supplypilot.document.semantic;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "supplypilot.semantic.indexing", name = "worker-enabled",
        havingValue = "true", matchIfMissing = true)
public class SemanticIndexScheduler {
    private final SemanticIndexWorker worker;

    public SemanticIndexScheduler(SemanticIndexWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${supplypilot.semantic.indexing.poll-interval-millis:1000}")
    public void poll() {
        worker.processNext();
    }
}
