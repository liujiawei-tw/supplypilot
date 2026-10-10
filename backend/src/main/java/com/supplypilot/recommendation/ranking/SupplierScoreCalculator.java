package com.supplypilot.recommendation.ranking;

import java.math.BigDecimal;
import java.util.Objects;

/** Calculates the specified V1 weighted total from already-normalized component scores. */
public class SupplierScoreCalculator {

    private static final BigDecimal PRICE_WEIGHT = new BigDecimal("0.40");
    private static final BigDecimal LEAD_TIME_WEIGHT = new BigDecimal("0.30");
    private static final BigDecimal RELIABILITY_WEIGHT = new BigDecimal("0.20");
    private static final BigDecimal QUALITY_WEIGHT = new BigDecimal("0.10");

    public SupplierScore calculate(
            BigDecimal priceScore,
            BigDecimal leadTimeScore,
            BigDecimal reliabilityScore,
            BigDecimal qualityScore) {
        requireNonNull(priceScore);
        requireNonNull(leadTimeScore);
        requireNonNull(reliabilityScore);
        requireNonNull(qualityScore);

        BigDecimal totalScore = priceScore.multiply(PRICE_WEIGHT)
                .add(leadTimeScore.multiply(LEAD_TIME_WEIGHT))
                .add(reliabilityScore.multiply(RELIABILITY_WEIGHT))
                .add(qualityScore.multiply(QUALITY_WEIGHT));
        return new SupplierScore(priceScore, leadTimeScore, reliabilityScore, qualityScore, totalScore);
    }

    private static void requireNonNull(BigDecimal value) {
        Objects.requireNonNull(value, "Score must not be null");
    }
}
