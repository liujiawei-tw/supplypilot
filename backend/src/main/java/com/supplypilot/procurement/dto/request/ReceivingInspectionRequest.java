package com.supplypilot.procurement.dto.request;

import jakarta.validation.constraints.Size;

public record ReceivingInspectionRequest(
        boolean allItemsReceived,
        boolean allQuantitiesConfirmed,
        boolean allQualityAccepted,
        @Size(max = 2000) String note
) { }
