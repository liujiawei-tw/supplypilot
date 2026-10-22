package com.supplypilot.procurement.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReceivingInspectionRequest(
        @NotNull Boolean allItemsReceived,
        @NotNull Boolean allQuantitiesConfirmed,
        @NotNull Boolean allQualityAccepted,
        @Size(max = 2000) String note
) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }
}
