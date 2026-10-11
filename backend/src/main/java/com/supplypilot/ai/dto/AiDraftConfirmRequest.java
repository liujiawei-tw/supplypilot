package com.supplypilot.ai.dto;

import com.supplypilot.procurement.dto.request.CreatePurchaseOrderRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AiDraftConfirmRequest(
        @NotBlank @Size(max = 256) String confirmationToken,
        @NotNull CreatePurchaseOrderRequest draft
) { }
