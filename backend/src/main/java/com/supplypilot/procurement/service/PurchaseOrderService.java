package com.supplypilot.procurement.service;

import java.time.Clock;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.dto.request.CreatePurchaseOrderItemRequest;
import com.supplypilot.procurement.dto.request.CreatePurchaseOrderRequest;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import com.supplypilot.procurement.mapper.PurchaseOrderMapper;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.recommendation.domain.PurchaseRecommendation;
import com.supplypilot.recommendation.domain.SupplierRecommendationResult;
import com.supplypilot.recommendation.repository.PurchaseRecommendationRepository;
import com.supplypilot.recommendation.repository.SupplierRecommendationResultRepository;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.supplier.repository.SupplierMaterialRepository;
import com.supplypilot.supplier.repository.SupplierRepository;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.repository.UserRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseOrderService {
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierRepository supplierRepository;
    private final MaterialRepository materialRepository;
    private final SupplierMaterialRepository supplierMaterialRepository;
    private final PurchaseRecommendationRepository purchaseRecommendationRepository;
    private final SupplierRecommendationResultRepository supplierRecommendationResultRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final Clock clock;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository, SupplierRepository supplierRepository,
                                MaterialRepository materialRepository, SupplierMaterialRepository supplierMaterialRepository,
                                PurchaseRecommendationRepository purchaseRecommendationRepository,
                                SupplierRecommendationResultRepository supplierRecommendationResultRepository,
                                UserRepository userRepository, CurrentUserProvider currentUserProvider,
                                PurchaseOrderMapper purchaseOrderMapper, Clock clock) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.supplierRepository = supplierRepository;
        this.materialRepository = materialRepository;
        this.supplierMaterialRepository = supplierMaterialRepository;
        this.purchaseRecommendationRepository = purchaseRecommendationRepository;
        this.supplierRecommendationResultRepository = supplierRecommendationResultRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'ADMIN')")
    public PurchaseOrderResponse createDraft(CreatePurchaseOrderRequest request) {
        Supplier supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND));
        if (supplier.getStatus() != SupplierStatus.ACTIVE) throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE);
        User createdBy = userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        PurchaseOrder purchaseOrder = PurchaseOrder.create(supplier, request.expectedDeliveryDate(), createdBy, clock.instant());
        purchaseOrder.assignPendingPoNumber("P-" + UUID.randomUUID().toString().replace("-", ""));
        HashSet<Long> materialIds = new HashSet<>();
        for (CreatePurchaseOrderItemRequest itemRequest : request.items()) {
            if (!materialIds.add(itemRequest.materialId())) throw new BusinessException(ErrorCode.DUPLICATE_MATERIAL);
            Material material = materialRepository.findById(itemRequest.materialId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
            if (material.getStatus() != MaterialStatus.ACTIVE) throw new BusinessException(ErrorCode.MATERIAL_INACTIVE);
            SupplierMaterial supplierMaterial = supplierMaterialRepository.findBySupplierIdAndMaterialId(supplier.getId(), material.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_MATERIAL_NOT_FOUND));
            if (supplierMaterial.getStatus() != SupplierMaterialStatus.ACTIVE) {
                throw new BusinessException(ErrorCode.SUPPLIER_MATERIAL_INACTIVE);
            }
            purchaseOrder.addItem(createItem(itemRequest, material, supplier));
        }

        PurchaseOrder persisted = purchaseOrderRepository.saveAndFlush(purchaseOrder);
        persisted.assignDisplayPoNumber(displayPoNumber(persisted.getId(), LocalDate.now(clock)));
        return purchaseOrderMapper.toResponse(persisted);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public PurchaseOrderResponse getById(Long id) {
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND)));
    }

    private PurchaseOrderItem createItem(CreatePurchaseOrderItemRequest request, Material material, Supplier supplier) {
        if (!request.hasRecommendationInput()) {
            return PurchaseOrderItem.create(material, request.quantity(), request.unitPrice(), clock.instant());
        }

        validateRecommendationInputShape(request);
        PurchaseRecommendation recommendation = purchaseRecommendationRepository.findById(request.recommendationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOMMENDATION_NOT_FOUND));
        if (!recommendation.getMaterial().getId().equals(material.getId())) {
            throw new BusinessException(ErrorCode.INVALID_RECOMMENDATION_REFERENCE);
        }

        SupplierRecommendationResult supplierResult = supplierRecommendationResultRepository
                .findById(request.supplierRecommendationResultId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_SUPPLIER_RECOMMENDATION_REFERENCE));
        if (!supplierResult.getRecommendation().getId().equals(recommendation.getId())
                || !supplierResult.getSupplier().getId().equals(supplier.getId())) {
            throw new BusinessException(ErrorCode.INVALID_SUPPLIER_RECOMMENDATION_REFERENCE);
        }

        Long recommendedSupplierId = recommendation.getRecommendedSupplier() == null
                ? null : recommendation.getRecommendedSupplier().getId();
        BigDecimal recommendedQuantity = recommendation.getRecommendedQuantity();
        boolean supplierOverride = !Objects.equals(supplier.getId(), recommendedSupplierId);
        boolean quantityOverride = recommendedQuantity == null
                || request.quantity().compareTo(recommendedQuantity) != 0;
        String supplierOverrideReason = overrideReason(
                supplierOverride, request.supplierOverrideReason(), ErrorCode.SUPPLIER_OVERRIDE_REASON_REQUIRED);
        String quantityOverrideReason = overrideReason(
                quantityOverride, request.quantityOverrideReason(), ErrorCode.QUANTITY_OVERRIDE_REASON_REQUIRED);

        return PurchaseOrderItem.createFromRecommendation(
                material,
                request.quantity(),
                request.unitPrice(),
                recommendation.getId(),
                supplierResult.getId(),
                recommendedSupplierId,
                recommendedQuantity,
                supplierOverrideReason,
                quantityOverrideReason,
                clock.instant());
    }

    private void validateRecommendationInputShape(CreatePurchaseOrderItemRequest request) {
        if (request.recommendationId() == null) {
            throw new BusinessException(ErrorCode.INVALID_RECOMMENDATION_REFERENCE);
        }
        if (request.supplierRecommendationResultId() == null) {
            throw new BusinessException(ErrorCode.INVALID_SUPPLIER_RECOMMENDATION_REFERENCE);
        }
    }

    private String overrideReason(boolean override, String reason, ErrorCode missingReasonError) {
        if (!override) return null;
        if (reason == null || reason.trim().isEmpty()) throw new BusinessException(missingReasonError);
        return reason;
    }

    private String displayPoNumber(Long id, LocalDate date) {
        return "PO-%04d%02d%02d-%06d".formatted(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), id);
    }
}
