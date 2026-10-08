package com.supplypilot.testsupport;

import java.math.BigDecimal;

public record TestMaterialData(
        String code,
        String name,
        String baseUnit,
        BigDecimal safetyStock,
        String status
) {
}
