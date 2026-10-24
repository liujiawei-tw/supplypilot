package com.supplypilot.procurement.dto.response;

import java.math.BigDecimal;

import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.supplier.domain.Currency;

public record PurchaseOrderItemResponse(
        Long id,
        Long materialId,
        String materialCode,
        String materialName,
        BigDecimal quantity,
        BaseUnit unit,
        BigDecimal unitPrice,
        BigDecimal total,
        Currency currency,
        Long recommendationId,
        Long supplierRecommendationResultId,
        boolean supplierOverride,
        boolean quantityOverride,
        String supplierOverrideReason,
        String quantityOverrideReason
) { }
