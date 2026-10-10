package com.supplypilot.recommendation.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public class CreateRecommendationRequest {

    @NotNull
    private Long materialId;

    @NotNull
    private LocalDate periodStart;

    @NotNull
    private LocalDate periodEnd;

    public CreateRecommendationRequest() {
    }

    public CreateRecommendationRequest(Long materialId, LocalDate periodStart, LocalDate periodEnd) {
        this.materialId = materialId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    @JsonSetter("materialId")
    public void setMaterialId(Long materialId) { this.materialId = materialId; }

    @JsonSetter("periodStart")
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }

    @JsonSetter("periodEnd")
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }

    public Long materialId() { return materialId; }
    public LocalDate periodStart() { return periodStart; }
    public LocalDate periodEnd() { return periodEnd; }
}
