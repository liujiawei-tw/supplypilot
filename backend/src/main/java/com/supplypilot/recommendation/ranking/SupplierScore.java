package com.supplypilot.recommendation.ranking;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Score components supplied by the normalization policy.
 *
 * <p>The V1 specifications define the component weights, but do not yet define price or lead-time
 * normalization, a score range, or missing-data handling. This value object intentionally does not
 * impose any of those policies.</p>
 */
public record SupplierScore(
        BigDecimal priceScore,
        BigDecimal leadTimeScore,
        BigDecimal reliabilityScore,
        BigDecimal qualityScore,
        BigDecimal totalScore) {

    public SupplierScore {
        Objects.requireNonNull(priceScore, "Price score must not be null");
        Objects.requireNonNull(leadTimeScore, "Lead-time score must not be null");
        Objects.requireNonNull(reliabilityScore, "Reliability score must not be null");
        Objects.requireNonNull(qualityScore, "Quality score must not be null");
        Objects.requireNonNull(totalScore, "Total score must not be null");
    }
}
