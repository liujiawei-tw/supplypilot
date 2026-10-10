package com.supplypilot.recommendation.ranking;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Calculates the specified V1 supplier score from qualified-candidate normalization baselines. */
public class SupplierScoreCalculator {

    private static final BigDecimal PRICE_WEIGHT = new BigDecimal("0.40");
    private static final BigDecimal LEAD_TIME_WEIGHT = new BigDecimal("0.30");
    private static final BigDecimal RELIABILITY_WEIGHT = new BigDecimal("0.20");
    private static final BigDecimal QUALITY_WEIGHT = new BigDecimal("0.10");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int NORMALIZATION_SCALE = 12;

    public SupplierScore calculate(
            BigDecimal totalPurchaseCost,
            BigDecimal lowestTotalPurchaseCost,
            Integer leadTimeDays,
            Integer shortestLeadTimeDays,
            BigDecimal reliabilityScore,
            BigDecimal qualityScore) {
        BigDecimal validTotalPurchaseCost = requireNonNegative(totalPurchaseCost, "Total purchase cost");
        BigDecimal validLowestTotalPurchaseCost = requireNonNegative(lowestTotalPurchaseCost, "Lowest total purchase cost");
        int validLeadTimeDays = requireNonNegative(leadTimeDays, "Lead time days");
        int validShortestLeadTimeDays = requireNonNegative(shortestLeadTimeDays, "Shortest lead time days");
        BigDecimal validReliabilityScore = requireScore(reliabilityScore, "Reliability score");
        BigDecimal validQualityScore = requireScore(qualityScore, "Quality score");
        if (validLowestTotalPurchaseCost.compareTo(validTotalPurchaseCost) > 0
                || validShortestLeadTimeDays > validLeadTimeDays) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        BigDecimal priceScore = calculatePriceScore(validTotalPurchaseCost, validLowestTotalPurchaseCost);
        BigDecimal leadTimeScore = calculateLeadTimeScore(validLeadTimeDays, validShortestLeadTimeDays);

        BigDecimal totalScore = priceScore.multiply(PRICE_WEIGHT)
                .add(leadTimeScore.multiply(LEAD_TIME_WEIGHT))
                .add(validReliabilityScore.multiply(RELIABILITY_WEIGHT))
                .add(validQualityScore.multiply(QUALITY_WEIGHT));
        return new SupplierScore(priceScore, leadTimeScore, validReliabilityScore, validQualityScore, totalScore);
    }

    private static BigDecimal calculatePriceScore(BigDecimal totalPurchaseCost, BigDecimal lowestTotalPurchaseCost) {
        if (lowestTotalPurchaseCost.compareTo(BigDecimal.ZERO) == 0) {
            return totalPurchaseCost.compareTo(BigDecimal.ZERO) == 0 ? ONE_HUNDRED : BigDecimal.ZERO;
        }
        return lowestTotalPurchaseCost.divide(totalPurchaseCost, NORMALIZATION_SCALE, RoundingMode.HALF_UP)
                .multiply(ONE_HUNDRED);
    }

    private static BigDecimal calculateLeadTimeScore(int leadTimeDays, int shortestLeadTimeDays) {
        return BigDecimal.valueOf(1L + shortestLeadTimeDays)
                .divide(BigDecimal.valueOf(1L + leadTimeDays), NORMALIZATION_SCALE, RoundingMode.HALF_UP)
                .multiply(ONE_HUNDRED);
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private static int requireNonNegative(Integer value, String name) {
        if (value == null || value < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private static BigDecimal requireScore(BigDecimal value, String name) {
        BigDecimal score = requireNonNegative(value, name);
        if (score.compareTo(ONE_HUNDRED) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return score;
    }
}
