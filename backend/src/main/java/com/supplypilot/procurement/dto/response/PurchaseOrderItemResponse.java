package com.supplypilot.procurement.dto.response;

import java.math.BigDecimal;

import com.supplypilot.material.domain.BaseUnit;

public record PurchaseOrderItemResponse(
        Long id,
        Long materialId,
        String materialCode,
        String materialName,
        BigDecimal quantity,
        BaseUnit unit,
        BigDecimal unitPrice,
        BigDecimal total,
        Long recommendationId,
        Long supplierRecommendationResultId,
        boolean supplierOverride,
        boolean quantityOverride
) { }
