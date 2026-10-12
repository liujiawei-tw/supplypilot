package com.supplypilot.recommendation.dto.response;

import com.supplypilot.recommendation.domain.DisqualificationReason;
import java.math.BigDecimal;

public record SupplierRecommendationResultResponse(
        Long id,
        Long supplierId,
        String supplierCode,
        String supplierName,
        boolean qualified,
        DisqualificationReason disqualifiedReason,
        BigDecimal recommendedQuantity,
        BigDecimal unitPrice,
        BigDecimal totalCost,
        Integer leadTimeDays,
        BigDecimal reliabilityScoreSnapshot,
        BigDecimal qualityScoreSnapshot,
        BigDecimal priceScore,
        BigDecimal leadTimeScore,
        BigDecimal reliabilityScore,
        BigDecimal qualityScore,
        BigDecimal totalScore,
        Integer rank) { }
