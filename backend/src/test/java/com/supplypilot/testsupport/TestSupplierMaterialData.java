package com.supplypilot.testsupport;

import java.math.BigDecimal;

public record TestSupplierMaterialData(
        String supplierCode,
        String materialCode,
        BigDecimal unitPrice,
        BigDecimal minimumOrderQuantity,
        BigDecimal packageMultiple,
        int leadTimeDays,
        String status
) {
}
