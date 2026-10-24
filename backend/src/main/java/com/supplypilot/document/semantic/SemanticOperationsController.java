package com.supplypilot.document.semantic;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/semantic-index")
public class SemanticOperationsController {
    private final SemanticBackfillService backfill;
    private final SemanticReadinessService readiness;
    private final SemanticOutboundApprovalService approval;
    private final SemanticIndexJobService jobs;

    public SemanticOperationsController(SemanticBackfillService backfill, SemanticReadinessService readiness,
                                        SemanticOutboundApprovalService approval, SemanticIndexJobService jobs) {
        this.backfill = backfill;
        this.readiness = readiness;
        this.approval = approval;
        this.jobs = jobs;
    }

    @PostMapping("/backfill")
    public SemanticBackfillService.BackfillBatch backfill(@RequestParam(defaultValue = "0") long afterId,
                                                           @RequestParam(defaultValue = "25") int limit) {
        return backfill.backfill(afterId, limit);
    }

    @GetMapping("/readiness")
    public SemanticReadinessService.GateReport readiness() {
        return readiness.report();
    }

    @PostMapping("/gate-audit")
    public SemanticReadinessService.GateReport audit() {
        return readiness.audit();
    }


    @GetMapping("/jobs")
    public java.util.List<SemanticIndexJobResponse> jobs(@RequestParam(defaultValue = "50") int limit) {
        return jobs.latest(limit);
    }

    @PostMapping("/jobs/{jobId}/retry")
    public SemanticIndexJobResponse retry(@PathVariable long jobId) {
        return jobs.retry(jobId);
    }
    @PostMapping("/documents/{id}/approve-outbound")
    public SemanticOutboundApprovalService.Review approve(@PathVariable long id) {
        return approval.approve(id);
    }

    @PostMapping("/documents/{id}/exclude-outbound")
    public SemanticOutboundApprovalService.Review exclude(@PathVariable long id) {
        return approval.exclude(id);
    }
}
