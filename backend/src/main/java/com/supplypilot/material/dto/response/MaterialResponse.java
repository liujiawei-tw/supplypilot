package com.supplypilot.material.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.MaterialStatus;

public record MaterialResponse(
        Long id,
        String code,
        String name,
        BaseUnit baseUnit,
        BigDecimal safetyStock,
        MaterialStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
