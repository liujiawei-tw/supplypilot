package com.supplypilot.recommendation.ranking;

import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import java.math.BigDecimal;
import java.util.Objects;

/** Immutable ranked, qualified supplier candidate. */
public record SupplierRankingResult(
        int rank,
        Supplier supplier,
        SupplierMaterial supplierMaterial,
        BigDecimal recommendedQuantity,
        BigDecimal totalPurchaseCost,
        SupplierScore score) {

    public SupplierRankingResult {
        if (rank < 1) {
            throw new IllegalArgumentException("Rank must be at least one");
        }
        Objects.requireNonNull(supplier, "Supplier must not be null");
        Objects.requireNonNull(supplierMaterial, "Supplier material must not be null");
        Objects.requireNonNull(recommendedQuantity, "Recommended quantity must not be null");
        Objects.requireNonNull(totalPurchaseCost, "Total purchase cost must not be null");
        Objects.requireNonNull(score, "Score must not be null");
    }
}
