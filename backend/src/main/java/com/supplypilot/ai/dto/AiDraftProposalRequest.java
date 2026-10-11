package com.supplypilot.ai.dto;

import com.supplypilot.procurement.dto.request.CreatePurchaseOrderRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiDraftProposalRequest(
        @NotBlank @Size(max = 2000) String message,
        CreatePurchaseOrderRequest draft
) { }
