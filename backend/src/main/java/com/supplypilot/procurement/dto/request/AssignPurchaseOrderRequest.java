package com.supplypilot.procurement.dto.request;

import jakarta.validation.constraints.NotNull;

public record AssignPurchaseOrderRequest(@NotNull Long userId) { }
