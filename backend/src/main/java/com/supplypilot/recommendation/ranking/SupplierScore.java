package com.supplypilot.recommendation.ranking;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Backend-calculated supplier score components.
 */
public record SupplierScore(
        BigDecimal priceScore,
        BigDecimal leadTimeScore,
        BigDecimal reliabilityScore,
        BigDecimal qualityScore,
        BigDecimal totalScore) {

    public SupplierScore {
        requireScoreInRange(priceScore, "Price score");
        requireScoreInRange(leadTimeScore, "Lead-time score");
        requireScoreInRange(reliabilityScore, "Reliability score");
        requireScoreInRange(qualityScore, "Quality score");
        requireScoreInRange(totalScore, "Total score");
    }

    private static void requireScoreInRange(BigDecimal score, String name) {
        Objects.requireNonNull(score, name + " must not be null");
        if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }
}
