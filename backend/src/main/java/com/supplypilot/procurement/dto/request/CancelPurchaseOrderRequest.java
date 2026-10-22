package com.supplypilot.procurement.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelPurchaseOrderRequest(@NotBlank @Size(max = 2000) String reason) { }
