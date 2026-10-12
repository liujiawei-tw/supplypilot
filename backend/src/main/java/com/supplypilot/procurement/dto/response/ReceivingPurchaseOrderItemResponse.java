package com.supplypilot.procurement.dto.response;

import java.math.BigDecimal;

import com.supplypilot.material.domain.BaseUnit;

public record ReceivingPurchaseOrderItemResponse(
        Long id,
        Long materialId,
        String materialCode,
        String materialName,
        BigDecimal quantity,
        BaseUnit unit
) { }
