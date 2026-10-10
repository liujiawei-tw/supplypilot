package com.supplypilot.recommendation.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private final SupplierScoreCalculator scoreCalculator = new SupplierScoreCalculator();
    private final SupplierRankingService rankingService = new SupplierRankingService(
            new PurchaseQuantityCalculator(), new SupplierConstraintEvaluator());

    @Test
    void shouldCalculateWeightedTotalExactlyWithoutRounding() {
        SupplierScore score = scoreCalculator.calculate(
                new BigDecimal("80.125"), new BigDecimal("90.375"), new BigDecimal("88.5"), new BigDecimal("95.25"));

        assertThat(score.totalScore()).isEqualByComparingTo("86.3875");
    }

    @Test
    void shouldRejectMissingScoreComponent() {
        assertThatThrownBy(() -> scoreCalculator.calculate(null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRankHigherTotalScoreFirst() {
        SupplierRankingInput lower = candidate("SUP-002", "90", "10", "100", "5", "80", "80");
        SupplierRankingInput higher = candidate("SUP-001", "110", "10", "100", "5", "90", "90");

        List<SupplierRankingResult> results = rankingService.rank(List.of(lower, higher));

        assertThat(results).extracting(result -> result.supplier().getCode()).containsExactly("SUP-001", "SUP-002");
        assertThat(results).extracting(SupplierRankingResult::rank).containsExactly(1, 2);
    }

    @Test
    void shouldBreakEqualScoresByLowerTotalPurchaseCostUsingRecommendedQuantity() {
        SupplierRankingInput moreQuantityButLowerUnitPrice = candidate("SUP-002", "9", "20", "100", "5", "80", "80");
        SupplierRankingInput lessQuantityButHigherUnitPrice = candidate("SUP-001", "10", "15", "100", "5", "80", "80");

        List<SupplierRankingResult> results = rankingService.rank(List.of(moreQuantityButLowerUnitPrice, lessQuantityButHigherUnitPrice));

        assertThat(results).extracting(result -> result.supplier().getCode()).containsExactly("SUP-001", "SUP-002");
        assertThat(results).extracting(SupplierRankingResult::totalPurchaseCost)
                .extracting(BigDecimal::toPlainString).containsExactly("150", "180");
    }

    @Test
    void shouldBreakEqualScoresAndCostsByShorterLeadTime() {
        SupplierRankingInput slower = candidate("SUP-001", "10", "10", "100", "8", "80", "80");
        SupplierRankingInput faster = candidate("SUP-002", "10", "10", "100", "3", "80", "80");

        assertThat(rankingService.rank(List.of(slower, faster)))
                .extracting(result -> result.supplier().getCode()).containsExactly("SUP-002", "SUP-001");
    }

    @Test
    void shouldBreakEqualScoresCostsAndLeadTimesByHigherReliability() {
        SupplierRankingInput lessReliable = candidate("SUP-001", "10", "10", "70", "5", "80", "80");
        SupplierRankingInput moreReliable = candidate("SUP-002", "10", "10", "90", "5", "80", "80");

        assertThat(rankingService.rank(List.of(lessReliable, moreReliable)))
                .extracting(result -> result.supplier().getCode()).containsExactly("SUP-002", "SUP-001");
    }

    @Test
    void shouldBreakCompleteTieBySupplierCode() {
        SupplierRankingInput laterCode = candidate("SUP-002", "10", "10", "80", "5", "80", "80");
        SupplierRankingInput earlierCode = candidate("SUP-001", "10", "10", "80", "5", "80", "80");

        assertThat(rankingService.rank(List.of(laterCode, earlierCode)))
                .extracting(result -> result.supplier().getCode()).containsExactly("SUP-001", "SUP-002");
    }

    @Test
    void shouldProduceSameRankingRegardlessOfInputOrder() {
        SupplierRankingInput first = candidate("SUP-001", "10", "10", "80", "5", "80", "80");
        SupplierRankingInput second = candidate("SUP-002", "10", "10", "80", "5", "80", "80");

        assertThat(rankingService.rank(List.of(first, second)))
                .extracting(result -> result.supplier().getCode()).containsExactly("SUP-001", "SUP-002");
        assertThat(rankingService.rank(List.of(second, first)))
                .extracting(result -> result.supplier().getCode()).containsExactly("SUP-001", "SUP-002");
    }

    @Test
    void shouldExcludeDisqualifiedCandidatesEvenWhenTheyHaveTheHighestScore() {
        SupplierRankingInput qualified = candidate("SUP-001", "10", "10", "80", "5", "80", "80");
        SupplierRankingInput disqualified = candidate("SUP-002", "10", "10", "99", "5", "100", "100");
        disqualified.supplier().update(null, null, null, null, SupplierStatus.INACTIVE, TestDataConstants.TEST_INSTANT.plusSeconds(1));

        assertThat(rankingService.rank(List.of(disqualified, qualified)))
                .extracting(result -> result.supplier().getCode()).containsExactly("SUP-001");
    }

    @Test
    void shouldHandleEmptyAndSingleQualifiedCandidateLists() {
        SupplierRankingInput only = candidate("SUP-001", "10", "10", "80", "5", "80", "80");

        assertThat(rankingService.rank(List.of())).isEmpty();
        assertThat(rankingService.rank(List.of(only))).singleElement().satisfies(result -> {
            assertThat(result.rank()).isEqualTo(1);
            assertThat(result.supplier().getCode()).isEqualTo("SUP-001");
        });
    }

    @Test
    void shouldRejectNegativeRecommendedQuantity() {
        assertThatThrownBy(() -> candidate("SUP-001", "10", "-1", "80", "5", "80", "80"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Recommended quantity");
    }

    private SupplierRankingInput candidate(
            String code, String unitPrice, String quantity, String reliability, String leadTime, String priceScore, String leadTimeScore) {
        Supplier supplier = Supplier.create(
                code, "Supplier " + code, null, new BigDecimal(reliability), new BigDecimal("80"), TestDataConstants.TEST_INSTANT);
        Material material = Material.create("MAT-" + code, "Material", BaseUnit.KG, BigDecimal.ZERO, TestDataConstants.TEST_INSTANT);
        SupplierMaterial supplierMaterial = SupplierMaterial.create(
                supplier,
                material,
                new BigDecimal(unitPrice),
                BigDecimal.ONE,
                null,
                Integer.parseInt(leadTime),
                Currency.TWD,
                TestDataConstants.TEST_INSTANT);
        SupplierScore score = scoreCalculator.calculate(
                new BigDecimal(priceScore), new BigDecimal(leadTimeScore), supplier.getReliabilityScore(), supplier.getQualityScore());
        return new SupplierRankingInput(
                supplier,
                supplierMaterial,
                material,
                new BigDecimal(quantity),
                LocalDate.of(2026, 10, 8),
                LocalDate.of(2026, 10, 20),
                score);
    }
}
