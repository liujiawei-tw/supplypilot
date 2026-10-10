package com.supplypilot.recommendation.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.recommendation.calculator.PurchaseQuantityCalculator;
import com.supplypilot.supplier.domain.Currency;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.testsupport.TestDataConstants;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplierRankingServiceTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private static final LocalDate REQUIRED_DATE = LocalDate.of(2026, 10, 20);

    private final PurchaseQuantityCalculator purchaseQuantityCalculator = new PurchaseQuantityCalculator();
    private final SupplierScoreCalculator scoreCalculator = new SupplierScoreCalculator();
    private final SupplierRankingService rankingService = new SupplierRankingService(
            purchaseQuantityCalculator, new SupplierConstraintEvaluator(), scoreCalculator);

    @Test
    void shouldNormalizePriceScoreUsingLowestQualifiedTotalPurchaseCost() {
        SupplierScore score = scoreCalculator.calculate(
                new BigDecimal("200"), new BigDecimal("100"), 5, 5, new BigDecimal("80"), new BigDecimal("90"));

        assertThat(score.priceScore()).isEqualByComparingTo("50.000000000000");
    }

    @Test
    void shouldNormalizeLeadTimeScoreAndAllowZeroLeadTime() {
        SupplierScore zeroLeadTime = scoreCalculator.calculate(
                BigDecimal.ONE, BigDecimal.ONE, 0, 0, new BigDecimal("80"), new BigDecimal("90"));
        SupplierScore longerLeadTime = scoreCalculator.calculate(
                BigDecimal.ONE, BigDecimal.ONE, 1, 0, new BigDecimal("80"), new BigDecimal("90"));

        assertThat(zeroLeadTime.leadTimeScore()).isEqualByComparingTo("100");
        assertThat(longerLeadTime.leadTimeScore()).isEqualByComparingTo("50.000000000000");
    }

    @Test
    void shouldHandleZeroCostWithoutDividingByZero() {
        SupplierScore zeroCost = scoreCalculator.calculate(
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        SupplierScore positiveCost = scoreCalculator.calculate(
                BigDecimal.ONE, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(zeroCost.priceScore()).isEqualByComparingTo("100");
        assertThat(positiveCost.priceScore()).isEqualByComparingTo("0");
    }

    @Test
    void shouldGiveAllCandidatesFullNormalizedScoresWhenCostsAndLeadTimesMatch() {
        SupplierRankingInput first = candidate("SUP-001", "10", "10", "80", "90", 5);
        SupplierRankingInput second = candidate("SUP-002", "10", "10", "80", "90", 5);

        assertThat(rankingService.rank(List.of(second, first)))
                .extracting(SupplierRankingResult::score)
                .allSatisfy(score -> {
                    assertThat(score.priceScore()).isEqualByComparingTo("100");
                    assertThat(score.leadTimeScore()).isEqualByComparingTo("100");
                });
    }

    @Test
    void shouldCalculateAndRankBaselineSuppliersUsingFullPrecision() {
        List<SupplierRankingResult> results = rankingService.rank(List.of(
                baselineCandidate("SUP-001", "100", "800", "100", "5", "92", "90"),
                baselineCandidate("SUP-002", "104", "500", "50", "3", "85", "91"),
                baselineCandidate("SUP-003", "97", "1000", "100", "10", "88", "86")));

        assertThat(results).extracting(result -> result.supplier().getCode())
                .containsExactly("SUP-002", "SUP-001", "SUP-003");
        assertThat(results).extracting(result -> result.score().totalScore())
                .extracting(value -> value.setScale(2, java.math.RoundingMode.HALF_UP))
                .containsExactly(new BigDecimal("96.10"), new BigDecimal("86.40"), new BigDecimal("69.27"));
        assertThat(results.get(2).score().totalScore()).isEqualByComparingTo("69.27403936268000");
    }

    @Test
    void shouldReturnSingleQualifiedCandidateWithFullNormalizedScores() {
        SupplierRankingResult result = rankingService.rank(List.of(candidate("SUP-001", "10", "10", "80", "90", 5))).getFirst();

        assertThat(result.rank()).isEqualTo(1);
        assertThat(result.score().priceScore()).isEqualByComparingTo("100");
        assertThat(result.score().leadTimeScore()).isEqualByComparingTo("100");
    }

    @Test
    void shouldReturnEmptyRankingWhenNoCandidateQualifies() {
        SupplierRankingInput inactive = candidate("SUP-001", "10", "10", "80", "90", 5);
        inactive.supplier().update(null, null, null, null, SupplierStatus.INACTIVE, TestDataConstants.TEST_INSTANT.plusSeconds(1));

        assertThat(rankingService.rank(List.of(inactive))).isEmpty();
    }

    @Test
    void shouldExcludeDisqualifiedCandidateFromNormalizationBaselines() {
        SupplierRankingInput qualified = candidate("SUP-001", "10", "10", "80", "90", 5);
        SupplierRankingInput inactiveCheaper = candidate("SUP-002", "1", "10", "80", "90", 5);
        inactiveCheaper.supplier().update(null, null, null, null, SupplierStatus.INACTIVE, TestDataConstants.TEST_INSTANT.plusSeconds(1));

        SupplierRankingResult result = rankingService.rank(List.of(qualified, inactiveCheaper)).getFirst();

        assertThat(result.score().priceScore()).isEqualByComparingTo("100");
        assertThat(result.score().leadTimeScore()).isEqualByComparingTo("100");
    }

    @Test
    void shouldUseCalculatedScoreRatherThanCallerProvidedValue() {
        SupplierRankingInput higherMasterDataScore = candidate("SUP-001", "10", "10", "100", "100", 5);
        SupplierRankingInput lowerMasterDataScore = candidate("SUP-002", "10", "10", "0", "0", 5);

        assertThat(rankingService.rank(List.of(lowerMasterDataScore, higherMasterDataScore)))
                .extracting(result -> result.supplier().getCode()).containsExactly("SUP-001", "SUP-002");
    }

    @Test
    void shouldUseTotalCostTieBreakWithExactTotalScore() {
        SupplierRankingInput lowerCost = candidate("SUP-001", "10", "10", "50", "80", 5);
        SupplierRankingInput higherCost = candidate("SUP-002", "12.5", "10", "90", "80", 5);

        assertThat(rankingService.rank(List.of(higherCost, lowerCost)))
                .extracting(result -> result.supplier().getCode()).containsExactly("SUP-001", "SUP-002");
    }

    @Test
    void shouldUseLeadTimeTieBreakBeforeReliability() {
        SupplierRankingInput shorterLeadTime = candidate("SUP-001", "10", "10", "40", "80", 3);
        SupplierRankingInput longerLeadTime = candidate("SUP-002", "10", "10", "89.99999999995", "80", 5);

        assertThat(rankingService.rank(List.of(longerLeadTime, shorterLeadTime)))
                .extracting(result -> result.supplier().getCode()).containsExactly("SUP-001", "SUP-002");
    }

    @Test
    void shouldUseReliabilityThenSupplierCodeTieBreakWithStableInputOrder() {
        SupplierRankingInput lowerReliability = candidate("SUP-003", "10", "10", "50", "100", 5);
        SupplierRankingInput higherReliability = candidate("SUP-002", "10", "10", "60", "80", 5);
        SupplierRankingInput sameAsHigherReliability = candidate("SUP-001", "10", "10", "60", "80", 5);

        assertThat(rankingService.rank(List.of(lowerReliability, higherReliability, sameAsHigherReliability)))
                .extracting(result -> result.supplier().getCode()).containsExactly("SUP-001", "SUP-002", "SUP-003");
        assertThat(rankingService.rank(List.of(sameAsHigherReliability, lowerReliability, higherReliability)))
                .extracting(result -> result.supplier().getCode()).containsExactly("SUP-001", "SUP-002", "SUP-003");
    }

    @Test
    void shouldRejectDuplicateQualifiedSupplierCodes() {
        assertThatThrownBy(() -> rankingService.rank(List.of(
                candidate("SUP-001", "10", "10", "80", "90", 5),
                candidate("SUP-001", "10", "10", "80", "90", 5))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void shouldRejectNullAndInvalidInputs() {
        assertThatThrownBy(() -> rankingService.rank(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> rankingService.rank(java.util.Collections.singletonList(null))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> candidate("SUP-001", "10", "-1", "80", "90", 5))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> scoreCalculator.calculate(BigDecimal.ONE, BigDecimal.ONE, -1, 0, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> scoreCalculator.calculate(BigDecimal.ONE, new BigDecimal("2"), 0, 0, BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
    }

    private SupplierRankingInput baselineCandidate(
            String code, String unitPrice, String moq, String packageMultiple, String leadTime, String reliability, String quality) {
        BigDecimal recommendedQuantity = purchaseQuantityCalculator.calculate(
                new BigDecimal("750"), new BigDecimal(moq), new BigDecimal(packageMultiple));
        return candidate(code, unitPrice, recommendedQuantity.toPlainString(), reliability, quality, Integer.parseInt(leadTime));
    }

    private SupplierRankingInput candidate(
            String code, String unitPrice, String quantity, String reliability, String quality, int leadTimeDays) {
        Supplier supplier = Supplier.create(
                code, "Supplier " + code, null, new BigDecimal(reliability), new BigDecimal(quality), TestDataConstants.TEST_INSTANT);
        Material material = Material.create("MAT-" + code, "Material", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT);
        SupplierMaterial supplierMaterial = SupplierMaterial.create(
                supplier, material, new BigDecimal(unitPrice), BigDecimal.ONE, null, leadTimeDays, Currency.TWD, TestDataConstants.TEST_INSTANT);
        return new SupplierRankingInput(supplier, supplierMaterial, material, new BigDecimal(quantity), TODAY, REQUIRED_DATE);
    }
}
