package com.supplypilot.recommendation.ranking;

import com.supplypilot.material.domain.Material;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Immutable input for ranking one supplier candidate. */
public record SupplierRankingInput(
        Supplier supplier,
        SupplierMaterial supplierMaterial,
        Material requiredMaterial,
        BigDecimal recommendedQuantity,
        LocalDate currentDate,
        LocalDate requiredDate) {

    public SupplierRankingInput {
        if (supplier == null || supplierMaterial == null || requiredMaterial == null
                || recommendedQuantity == null || currentDate == null || requiredDate == null
                || recommendedQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
