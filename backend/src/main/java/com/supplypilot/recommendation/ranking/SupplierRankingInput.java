package com.supplypilot.recommendation.ranking;

import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Immutable input for ranking one supplier candidate. */
public record SupplierRankingInput(
        Supplier supplier,
        SupplierMaterial supplierMaterial,
        com.supplypilot.material.domain.Material requiredMaterial,
        BigDecimal recommendedQuantity,
        LocalDate currentDate,
        LocalDate requiredDate,
        SupplierScore score) {

    public SupplierRankingInput {
        Objects.requireNonNull(supplier, "Supplier must not be null");
        Objects.requireNonNull(supplierMaterial, "Supplier material must not be null");
        Objects.requireNonNull(requiredMaterial, "Required material must not be null");
        Objects.requireNonNull(recommendedQuantity, "Recommended quantity must not be null");
        Objects.requireNonNull(currentDate, "Current date must not be null");
        Objects.requireNonNull(requiredDate, "Required date must not be null");
        Objects.requireNonNull(score, "Score must not be null");
        if (recommendedQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Recommended quantity must not be negative");
        }
    }
}
