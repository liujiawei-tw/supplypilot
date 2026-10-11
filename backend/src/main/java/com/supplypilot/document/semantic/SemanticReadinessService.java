package com.supplypilot.document.semantic;

import java.util.ArrayList;
import java.util.List;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.security.CurrentUserProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class SemanticReadinessService {
    private final DocumentMetadataRepository documents;
    private final DocumentChunkRepository chunks;
    private final OutboundDocumentPolicy policy;
    private final SemanticStore store;
    private final SemanticProperties properties;
    private final CurrentUserProvider currentUser;

    public SemanticReadinessService(DocumentMetadataRepository documents, DocumentChunkRepository chunks,
                                    OutboundDocumentPolicy policy, SemanticStore store,
                                    SemanticProperties properties, CurrentUserProvider currentUser) {
        this.documents = documents;
        this.chunks = chunks;
        this.policy = policy;
        this.store = store;
        this.properties = properties;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public GateReport report() {
        return inspect();
    }

    @PreAuthorize("hasRole('ADMIN')")
    public GateReport audit() {
        GateReport report = inspect();
        long auditId = store.audit(currentUser.getCurrentUserId(), report.ready(), report.skipped(),
                report.failed(), report.requiresReview(), report.pending());
        return new GateReport(report.passed(), properties.generationKey(), properties.modelId(),
                properties.dimensions(), report.ready(), report.skipped(), report.failed(),
                report.requiresReview(), report.pending(), report.findings(), auditId);
    }

    /** Called for every semantic query. A newly READY, changed, or revoked source closes the gate. */
    public boolean canRead() {
        return store.hasPassingAudit() && inspect().passed();
    }

    private GateReport inspect() {
        store.ensureGeneration();
        int ready = 0, skipped = 0, failed = 0, review = 0, pending = 0;
        List<Finding> findings = new ArrayList<>();
        long afterId = 0;
        while (true) {
            List<DocumentMetadata> page = documents.findBackfillPage(DocumentStatus.READY,
                    afterId, PageRequest.of(0, 100));
            if (page.isEmpty()) break;
            for (DocumentMetadata document : page) {
                afterId = document.getId();
                List<DocumentChunk> source = chunks.findByDocumentIdOrderByChunkIndex(document.getId());
                String hash = SemanticBackfillService.sourceHash(source);
                var decision = policy.inspect(document,
                        source.stream().map(DocumentChunk::getContent).toList(), hash);
                SemanticStore.IndexState index = store.state(document.getId(), document.getVersion());
                String status = index == null ? "PENDING" : index.status();
                String reason = index == null ? "NOT_INDEXED" : index.failureReason();
                if (index != null && (!hash.equals(index.sourceHash())
                        || index.expectedCount() != source.size())) {
                    status = "PENDING";
                    reason = "SOURCE_CHANGED";
                }
                if ("READY".equals(status) && (!"APPROVED".equals(decision.status())
                        || source.isEmpty() || !store.matchesSource(document.getId(), document.getVersion(),
                                SemanticBackfillService.sourceProof(source, decision.safeChunks())))) {
                    status = "PENDING";
                    reason = "INDEX_INCOMPLETE_OR_POLICY_CHANGED";
                }
                if ("SKIPPED".equals(status) && !"SKIPPED".equals(decision.status())) {
                    status = "PENDING";
                    reason = "POLICY_CHANGED";
                }
                switch (status) {
                    case "READY" -> ready++;
                    case "SKIPPED" -> skipped++;
                    case "FAILED" -> failed++;
                    case "REQUIRES_REVIEW" -> review++;
                    default -> pending++;
                }
                if (!"READY".equals(status)) {
                    findings.add(new Finding(document.getId(), document.getVersion(), status, reason));
                }
            }
            if (page.size() < 100) break;
        }
        return new GateReport(failed == 0 && review == 0 && pending == 0,
                properties.generationKey(), properties.modelId(), properties.dimensions(),
                ready, skipped, failed, review, pending, List.copyOf(findings), null);
    }

    public record Finding(long documentId, String version, String status, String reason) { }
    public record GateReport(boolean passed, String generationKey, String modelId, int dimensions,
                             int ready, int skipped, int failed, int requiresReview, int pending,
                             List<Finding> findings, Long auditId) { }
}
