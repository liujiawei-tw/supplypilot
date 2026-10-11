package com.supplypilot.ai.controller;

import com.supplypilot.ai.dto.AiQueryRequest;
import com.supplypilot.ai.dto.AiQueryResponse;
import com.supplypilot.ai.dto.AiDraftConfirmRequest;
import com.supplypilot.ai.dto.AiDraftProposalRequest;
import com.supplypilot.ai.dto.AiDraftProposalResponse;
import com.supplypilot.ai.service.AiAssistantService;
import com.supplypilot.ai.service.AiDraftProposalService;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiAssistantService assistant;
    private final AiDraftProposalService drafts;

    public AiController(AiAssistantService assistant, AiDraftProposalService drafts) {
        this.assistant = assistant;
        this.drafts = drafts;
    }

    @PostMapping("/query")
    public AiQueryResponse query(@Valid @RequestBody AiQueryRequest request) {
        return assistant.query(request.message());
    }

    @PostMapping("/purchase-draft-proposals")
    public AiDraftProposalResponse propose(@Valid @RequestBody AiDraftProposalRequest request) {
        return drafts.propose(request);
    }

    @PostMapping("/purchase-draft-proposals/{proposalId}/confirm")
    public PurchaseOrderResponse confirm(@PathVariable String proposalId,
                                         @Valid @RequestBody AiDraftConfirmRequest request) {
        return drafts.confirm(proposalId, request);
    }
}
