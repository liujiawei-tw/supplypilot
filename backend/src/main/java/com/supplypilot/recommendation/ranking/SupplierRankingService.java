package com.supplypilot.recommendation.ranking;

import com.supplypilot.recommendation.calculator.PurchaseQuantityCalculator;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Ranks only candidates that passed {@link SupplierConstraintEvaluator}. */
public class SupplierRankingService {

    private final PurchaseQuantityCalculator purchaseQuantityCalculator;
    private final SupplierConstraintEvaluator supplierConstraintEvaluator;

    public SupplierRankingService(
            PurchaseQuantityCalculator purchaseQuantityCalculator,
            SupplierConstraintEvaluator supplierConstraintEvaluator) {
        this.purchaseQuantityCalculator = Objects.requireNonNull(purchaseQuantityCalculator);
        this.supplierConstraintEvaluator = Objects.requireNonNull(supplierConstraintEvaluator);
    }

    public List<SupplierRankingResult> rank(List<SupplierRankingInput> candidates) {
        Objects.requireNonNull(candidates, "Candidates must not be null");

        Comparator<RankableCandidate> comparator = Comparator
                .comparing((RankableCandidate candidate) -> candidate.score().totalScore(), Comparator.reverseOrder())
                .thenComparing(RankableCandidate::totalPurchaseCost)
                .thenComparing(candidate -> candidate.input().supplierMaterial().getLeadTimeDays())
                .thenComparing(candidate -> candidate.input().supplier().getReliabilityScore(), Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.input().supplier().getCode());

        List<RankableCandidate> qualifiedCandidates = candidates.stream()
                .peek(candidate -> Objects.requireNonNull(candidate, "Candidate must not be null"))
                .filter(this::isQualified)
                .map(this::toRankableCandidate)
                .sorted(comparator)
                .toList();

        return java.util.stream.IntStream.range(0, qualifiedCandidates.size())
                .mapToObj(index -> toResult(index + 1, qualifiedCandidates.get(index)))
                .toList();
    }

    private boolean isQualified(SupplierRankingInput input) {
        return supplierConstraintEvaluator.evaluate(
                input.supplier(),
                input.supplierMaterial(),
                input.requiredMaterial(),
                input.currentDate(),
                input.requiredDate()).qualified();
    }

    private RankableCandidate toRankableCandidate(SupplierRankingInput input) {
        BigDecimal totalPurchaseCost = purchaseQuantityCalculator.calculateTotalPurchaseCost(
                input.recommendedQuantity(), input.supplierMaterial().getUnitPrice());
        return new RankableCandidate(input, totalPurchaseCost);
    }

    private SupplierRankingResult toResult(int rank, RankableCandidate candidate) {
        SupplierRankingInput input = candidate.input();
        return new SupplierRankingResult(
                rank,
                input.supplier(),
                input.supplierMaterial(),
                input.recommendedQuantity(),
                candidate.totalPurchaseCost(),
                input.score());
    }

    private record RankableCandidate(SupplierRankingInput input, BigDecimal totalPurchaseCost) {
        private SupplierScore score() {
            return input.score();
        }
    }
}
