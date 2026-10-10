package com.supplypilot.recommendation.ranking;

import com.supplypilot.recommendation.calculator.PurchaseQuantityCalculator;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Ranks only candidates that passed {@link SupplierConstraintEvaluator}. */
public class SupplierRankingService {

    private final PurchaseQuantityCalculator purchaseQuantityCalculator;
    private final SupplierConstraintEvaluator supplierConstraintEvaluator;
    private final SupplierScoreCalculator supplierScoreCalculator;

    public SupplierRankingService(
            PurchaseQuantityCalculator purchaseQuantityCalculator,
            SupplierConstraintEvaluator supplierConstraintEvaluator,
            SupplierScoreCalculator supplierScoreCalculator) {
        this.purchaseQuantityCalculator = Objects.requireNonNull(purchaseQuantityCalculator);
        this.supplierConstraintEvaluator = Objects.requireNonNull(supplierConstraintEvaluator);
        this.supplierScoreCalculator = Objects.requireNonNull(supplierScoreCalculator);
    }

    public List<SupplierRankingResult> rank(List<SupplierRankingInput> candidates) {
        if (candidates == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        List<CandidateCost> qualifiedCandidates = candidates.stream()
                .peek(this::requireCandidate)
                .filter(this::isQualified)
                .map(this::toCandidateCost)
                .toList();
        if (qualifiedCandidates.isEmpty()) {
            return List.of();
        }

        validateDistinctSupplierCodes(qualifiedCandidates);
        BigDecimal lowestTotalPurchaseCost = qualifiedCandidates.stream()
                .map(CandidateCost::totalPurchaseCost)
                .min(BigDecimal::compareTo)
                .orElseThrow();
        int shortestLeadTimeDays = qualifiedCandidates.stream()
                .map(candidate -> candidate.input().supplierMaterial().getLeadTimeDays())
                .min(Integer::compareTo)
                .orElseThrow();

        Comparator<RankableCandidate> comparator = Comparator
                .comparing((RankableCandidate candidate) -> candidate.score().totalScore(), Comparator.reverseOrder())
                .thenComparing(RankableCandidate::totalPurchaseCost)
                .thenComparing(candidate -> candidate.input().supplierMaterial().getLeadTimeDays())
                .thenComparing(candidate -> candidate.input().supplier().getReliabilityScore(), Comparator.reverseOrder())
                .thenComparing(candidate -> candidate.input().supplier().getCode());

        List<RankableCandidate> rankedCandidates = qualifiedCandidates.stream()
                .map(candidate -> toRankableCandidate(candidate, lowestTotalPurchaseCost, shortestLeadTimeDays))
                .sorted(comparator)
                .toList();

        return java.util.stream.IntStream.range(0, rankedCandidates.size())
                .mapToObj(index -> toResult(index + 1, rankedCandidates.get(index)))
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

    private void requireCandidate(SupplierRankingInput candidate) {
        if (candidate == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private CandidateCost toCandidateCost(SupplierRankingInput input) {
        BigDecimal totalPurchaseCost = purchaseQuantityCalculator.calculateTotalPurchaseCost(
                input.recommendedQuantity(), input.supplierMaterial().getUnitPrice());
        return new CandidateCost(input, totalPurchaseCost);
    }

    private RankableCandidate toRankableCandidate(
            CandidateCost candidate, BigDecimal lowestTotalPurchaseCost, int shortestLeadTimeDays) {
        SupplierRankingInput input = candidate.input();
        SupplierScore score = supplierScoreCalculator.calculate(
                candidate.totalPurchaseCost(),
                lowestTotalPurchaseCost,
                input.supplierMaterial().getLeadTimeDays(),
                shortestLeadTimeDays,
                input.supplier().getReliabilityScore(),
                input.supplier().getQualityScore());
        return new RankableCandidate(input, candidate.totalPurchaseCost(), score);
    }

    private void validateDistinctSupplierCodes(List<CandidateCost> candidates) {
        HashSet<String> supplierCodes = new HashSet<>();
        boolean unique = candidates.stream().map(candidate -> candidate.input().supplier().getCode()).allMatch(supplierCodes::add);
        if (!unique) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private SupplierRankingResult toResult(int rank, RankableCandidate candidate) {
        SupplierRankingInput input = candidate.input();
        return new SupplierRankingResult(
                rank,
                input.supplier(),
                input.supplierMaterial(),
                input.recommendedQuantity(),
                candidate.totalPurchaseCost(),
                candidate.score());
    }

    private record CandidateCost(SupplierRankingInput input, BigDecimal totalPurchaseCost) { }

    private record RankableCandidate(SupplierRankingInput input, BigDecimal totalPurchaseCost, SupplierScore score) { }
}
