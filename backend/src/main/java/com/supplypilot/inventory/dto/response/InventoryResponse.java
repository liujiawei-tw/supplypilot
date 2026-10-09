package com.supplypilot.inventory.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

import com.supplypilot.material.domain.BaseUnit;

public record InventoryResponse(
        Long materialId,
        String materialCode,
        String materialName,
        BigDecimal quantity,
        BaseUnit unit,
        Instant updatedAt
) {
}
