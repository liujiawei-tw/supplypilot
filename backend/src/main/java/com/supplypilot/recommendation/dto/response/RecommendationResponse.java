package com.supplypilot.recommendation.dto.response;

import com.supplypilot.recommendation.domain.RecommendationOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record RecommendationResponse(
        Long id,
        MaterialSnapshot material,
        Period period,
        Calculation calculation,
        RecommendationOutcome outcome,
        RecommendedSupplier recommendation,
        Instant createdAt) {

    public record MaterialSnapshot(Long id, String code, String name, String unit) { }

    public record Period(LocalDate start, LocalDate end) { }

    public record Calculation(
            BigDecimal expectedDemand,
            BigDecimal safetyStock,
            BigDecimal currentInventory,
            BigDecimal incomingInventory,
            BigDecimal netRequirement) { }

    public record RecommendedSupplier(
            boolean purchaseRequired,
            Long supplierId,
            String supplierName,
            BigDecimal quantity,
            BigDecimal score) { }
}
