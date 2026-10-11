package com.supplypilot.ai.dto;

import java.time.Instant;
import java.util.List;

import com.supplypilot.procurement.dto.request.CreatePurchaseOrderRequest;

public record AiDraftProposalResponse(
        String status,
        String proposalId,
        CreatePurchaseOrderRequest draft,
        Instant expiresAt,
        String confirmationToken,
        List<String> missingFields
) {
    public static AiDraftProposalResponse clarification(List<String> missingFields) {
        return new AiDraftProposalResponse("NEEDS_CLARIFICATION", null, null, null, null, missingFields);
    }
}
