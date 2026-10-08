package com.supplypilot.testsupport;

import java.math.BigDecimal;

public record TestSupplierData(
        String code,
        String name,
        BigDecimal reliability,
        BigDecimal quality,
        String status
) {
}
