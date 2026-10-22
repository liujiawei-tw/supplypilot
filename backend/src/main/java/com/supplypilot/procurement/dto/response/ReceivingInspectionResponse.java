package com.supplypilot.procurement.dto.response;

import java.time.Instant;

public record ReceivingInspectionResponse(
        boolean allItemsReceived,
        boolean allQuantitiesConfirmed,
        boolean allQualityAccepted,
        String note,
        Long inspectedBy,
        String inspectedByDisplayName,
        String inspectedByUsername,
        Instant inspectedAt
) { }
