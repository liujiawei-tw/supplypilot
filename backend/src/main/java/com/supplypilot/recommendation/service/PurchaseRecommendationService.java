package com.supplypilot.recommendation.service;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.demand.repository.DemandRepository;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.recommendation.calculator.NetRequirementCalculator;
import com.supplypilot.recommendation.calculator.PurchaseQuantityCalculator;
import com.supplypilot.recommendation.domain.PurchaseRecommendation;
import com.supplypilot.recommendation.domain.RecommendationOutcome;
import com.supplypilot.recommendation.domain.SupplierRecommendationResult;
import com.supplypilot.recommendation.dto.request.CreateRecommendationRequest;
import com.supplypilot.recommendation.dto.response.RecommendationResponse;
import com.supplypilot.recommendation.dto.response.SupplierRecommendationResultResponse;
import com.supplypilot.recommendation.mapper.RecommendationMapper;
import com.supplypilot.recommendation.query.IncomingInventoryQuery;
import com.supplypilot.recommendation.ranking.SupplierConstraintEvaluation;
import com.supplypilot.recommendation.ranking.SupplierConstraintEvaluator;
import com.supplypilot.recommendation.ranking.SupplierRankingInput;
import com.supplypilot.recommendation.ranking.SupplierRankingResult;
import com.supplypilot.recommendation.ranking.SupplierRankingService;
import com.supplypilot.recommendation.repository.PurchaseRecommendationRepository;
import com.supplypilot.recommendation.repository.SupplierRecommendationResultRepository;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseRecommendationService {

    private final PurchaseRecommendationRepository recommendationRepository;
    private final SupplierRecommendationResultRepository resultRepository;
    private final MaterialRepository materialRepository;
    private final DemandRepository demandRepository;
    private final InventoryRepository inventoryRepository;
    private final SupplierMaterialRepository supplierMaterialRepository;
    private final IncomingInventoryQuery incomingInventoryQuery;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final NetRequirementCalculator netRequirementCalculator;
    private final PurchaseQuantityCalculator purchaseQuantityCalculator;
    private final SupplierConstraintEvaluator supplierConstraintEvaluator;
    private final SupplierRankingService supplierRankingService;
    private final RecommendationMapper recommendationMapper;
    private final Clock clock;

    public PurchaseRecommendationService(
            PurchaseRecommendationRepository recommendationRepository,
            SupplierRecommendationResultRepository resultRepository,
            MaterialRepository materialRepository,
            DemandRepository demandRepository,
            InventoryRepository inventoryRepository,
            SupplierMaterialRepository supplierMaterialRepository,
            IncomingInventoryQuery incomingInventoryQuery,
            UserRepository userRepository,
            CurrentUserProvider currentUserProvider,
            NetRequirementCalculator netRequirementCalculator,
            PurchaseQuantityCalculator purchaseQuantityCalculator,
            SupplierConstraintEvaluator supplierConstraintEvaluator,
            SupplierRankingService supplierRankingService,
            RecommendationMapper recommendationMapper,
            Clock clock) {
        this.recommendationRepository = recommendationRepository;
        this.resultRepository = resultRepository;
        this.materialRepository = materialRepository;
        this.demandRepository = demandRepository;
        this.inventoryRepository = inventoryRepository;
        this.supplierMaterialRepository = supplierMaterialRepository;
        this.incomingInventoryQuery = incomingInventoryQuery;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.netRequirementCalculator = netRequirementCalculator;
        this.purchaseQuantityCalculator = purchaseQuantityCalculator;
        this.supplierConstraintEvaluator = supplierConstraintEvaluator;
        this.supplierRankingService = supplierRankingService;
        this.recommendationMapper = recommendationMapper;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public RecommendationResponse createRecommendation(CreateRecommendationRequest request) {
        validatePeriod(request.periodStart(), request.periodEnd());
        Material material = materialRepository.findById(request.materialId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
        if (material.getStatus() != MaterialStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.MATERIAL_INACTIVE);
        }
        Inventory inventory = inventoryRepository.findByMaterialId(material.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_NOT_FOUND));
        User createdBy = userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // V1 business decision: this is a period-end aggregated recommendation.  The same
        // required date governs incoming availability and supplier delivery feasibility.
        LocalDate requiredDate = request.periodEnd();
        BigDecimal expectedDemand = demandRepository.sumActiveDemand(
                material.getId(), request.periodStart(), request.periodEnd());
        BigDecimal incomingInventory = incomingInventoryQuery.calculateIncomingQuantity(material.getId(), requiredDate);
        BigDecimal netRequirement = netRequirementCalculator.calculate(
                expectedDemand, material.getSafetyStock(), inventory.getQuantity(), incomingInventory);
        Instant createdAt = clock.instant();

        if (netRequirement.signum() == 0) {
            PurchaseRecommendation recommendation = PurchaseRecommendation.create(
                    material, request.periodStart(), request.periodEnd(), expectedDemand, material.getSafetyStock(),
                    inventory.getQuantity(), incomingInventory, netRequirement,
                    RecommendationOutcome.PURCHASE_NOT_REQUIRED, null, null, null, createdBy, createdAt);
            return recommendationMapper.toResponse(recommendationRepository.save(recommendation));
        }

        List<SupplierMaterial> candidates = supplierMaterialRepository.findByMaterialIdOrderByIdAsc(material.getId());
        LocalDate currentDate = LocalDate.now(clock);
        Map<SupplierMaterial, SupplierConstraintEvaluation> evaluations = new HashMap<>();
        List<SupplierRankingInput> qualifiedInputs = candidates.stream()
                .map(candidate -> candidateInput(candidate, material, netRequirement, currentDate, requiredDate, evaluations))
                .filter(CandidateInput::qualified)
                .map(CandidateInput::input)
                .toList();
        List<SupplierRankingResult> ranking = supplierRankingService.rank(qualifiedInputs);

        RecommendationOutcome outcome = ranking.isEmpty()
                ? RecommendationOutcome.NO_QUALIFIED_SUPPLIER
                : RecommendationOutcome.SUPPLIER_RECOMMENDED;
        SupplierRankingResult topRanked = ranking.isEmpty() ? null : ranking.getFirst();
        PurchaseRecommendation recommendation = PurchaseRecommendation.create(
                material, request.periodStart(), request.periodEnd(), expectedDemand, material.getSafetyStock(),
                inventory.getQuantity(), incomingInventory, netRequirement, outcome,
                topRanked == null ? null : topRanked.supplier(),
                topRanked == null ? null : topRanked.recommendedQuantity(),
                topRanked == null ? null : topRanked.score().totalScore(), createdBy, createdAt);

        Map<Long, SupplierRankingResult> rankingBySupplierId = new HashMap<>();
        ranking.forEach(result -> rankingBySupplierId.put(result.supplier().getId(), result));
        for (SupplierMaterial candidate : candidates) {
            SupplierConstraintEvaluation evaluation = evaluations.get(candidate);
            SupplierRankingResult ranked = rankingBySupplierId.get(candidate.getSupplier().getId());
            recommendation.addSupplierResult(ranked == null
                    ? SupplierRecommendationResult.disqualified(recommendation, candidate.getSupplier(), evaluation.disqualificationReason())
                    : SupplierRecommendationResult.qualified(
                            recommendation, ranked.supplier(), ranked.recommendedQuantity(),
                            ranked.supplierMaterial().getUnitPrice(), ranked.totalPurchaseCost(),
                            ranked.supplierMaterial().getLeadTimeDays(), ranked.supplier().getReliabilityScore(),
                            ranked.supplier().getQualityScore(), ranked.score().priceScore(),
                            ranked.score().leadTimeScore(), ranked.score().reliabilityScore(),
                            ranked.score().qualityScore(), ranked.score().totalScore(), ranked.rank()));
        }

        return recommendationMapper.toResponse(recommendationRepository.save(recommendation));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PRODUCTION_PLANNER', 'PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'WAREHOUSE_STAFF', 'ADMIN')")
    public RecommendationResponse getRecommendation(Long id) {
        return recommendationMapper.toResponse(findRecommendation(id));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PRODUCTION_PLANNER', 'PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'WAREHOUSE_STAFF', 'ADMIN')")
    public List<SupplierRecommendationResultResponse> getSupplierResults(Long id) {
        findRecommendation(id);
        return resultRepository.findByRecommendationIdOrderByRankAsc(id).stream()
                .map(recommendationMapper::toResponse)
                .toList();
    }

    private CandidateInput candidateInput(
            SupplierMaterial candidate,
            Material material,
            BigDecimal netRequirement,
            LocalDate currentDate,
            LocalDate requiredDate,
            Map<SupplierMaterial, SupplierConstraintEvaluation> evaluations) {
        SupplierConstraintEvaluation evaluation = supplierConstraintEvaluator.evaluate(
                candidate.getSupplier(), candidate, material, currentDate, requiredDate);
        evaluations.put(candidate, evaluation);
        if (!evaluation.qualified()) {
            return CandidateInput.disqualified();
        }
        BigDecimal quantity = purchaseQuantityCalculator.calculate(
                netRequirement, candidate.getMoq(), candidate.getPackageMultiple());
        return CandidateInput.qualified(new SupplierRankingInput(
                candidate.getSupplier(), candidate, material, quantity, currentDate, requiredDate));
    }

    private PurchaseRecommendation findRecommendation(Long id) {
        return recommendationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND));
    }

    private void validatePeriod(LocalDate periodStart, LocalDate periodEnd) {
        if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }
    }

    private record CandidateInput(boolean qualified, SupplierRankingInput input) {
        static CandidateInput qualified(SupplierRankingInput input) { return new CandidateInput(true, input); }
        static CandidateInput disqualified() { return new CandidateInput(false, null); }
    }
}
