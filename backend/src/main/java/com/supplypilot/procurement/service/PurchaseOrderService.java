package com.supplypilot.procurement.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.dto.request.CreatePurchaseOrderItemRequest;
import com.supplypilot.procurement.dto.request.CreatePurchaseOrderRequest;
import com.supplypilot.procurement.dto.request.RejectPurchaseOrderRequest;
import com.supplypilot.procurement.dto.request.UpdatePurchaseOrderItemRequest;
import com.supplypilot.procurement.dto.request.UpdatePurchaseOrderRequest;
import com.supplypilot.procurement.dto.request.UpdateSupplierPromisedDeliveryDateRequest;
import com.supplypilot.procurement.dto.request.ReceivingInspectionRequest;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
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
        PurchaseOrder purchaseOrder = prepareDraft(request);
        PurchaseOrder persisted = purchaseOrderRepository.saveAndFlush(purchaseOrder);
        persisted.assignDisplayPoNumber(displayPoNumber(persisted.getId(), LocalDate.now(clock)));
        return purchaseOrderMapper.toResponse(persisted);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'ADMIN')")
    public void validateDraft(CreatePurchaseOrderRequest request) {
        prepareDraft(request);
    }

    private PurchaseOrder prepareDraft(CreatePurchaseOrderRequest request) {
        if (request == null || request.supplierId() == null || request.items() == null
                || request.items().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Supplier supplier = supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND));
        if (supplier.getStatus() != SupplierStatus.ACTIVE) throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE);
        User createdBy = userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        PurchaseOrder purchaseOrder = PurchaseOrder.create(supplier, request.expectedDeliveryDate(), createdBy, clock.instant());
        purchaseOrder.assignPendingPoNumber("P-" + UUID.randomUUID().toString().replace("-", ""));
        HashSet<Long> materialIds = new HashSet<>();
        for (CreatePurchaseOrderItemRequest itemRequest : request.items()) {
            if (itemRequest == null || itemRequest.materialId() == null
                    || itemRequest.quantity() == null || itemRequest.unitPrice() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
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

        return purchaseOrder;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public PurchaseOrderResponse getById(Long id) {
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND)));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public PageResponse<PurchaseOrderResponse> list(
            PurchaseOrderStatus status, Long supplierId, Long materialId,
            String poNumber, String supplierCode, String supplierName, String materialCode,
            LocalDate createdFrom, LocalDate createdTo,
            LocalDate expectedDeliveryFrom, LocalDate expectedDeliveryTo,
            Boolean overdue, Pageable pageable) {
        validateRange(createdFrom, createdTo);
        validateRange(expectedDeliveryFrom, expectedDeliveryTo);
        String poNumberPattern = containsPattern(poNumber);
        String supplierCodePattern = containsPattern(supplierCode);
        String supplierNamePattern = containsPattern(supplierName);
        String materialCodePattern = containsPattern(materialCode);
        LocalDate today = LocalDate.now(clock);
        ZoneId zone = clock.getZone();
        Specification<PurchaseOrder> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (supplierId != null) predicates.add(builder.equal(root.get("supplier").get("id"), supplierId));
            if (poNumberPattern != null) predicates.add(builder.like(
                    builder.lower(root.get("poNumber")), poNumberPattern, '!'));
            if (supplierCodePattern != null) predicates.add(builder.like(
                    builder.lower(root.get("supplier").get("code")), supplierCodePattern, '!'));
            if (supplierNamePattern != null) predicates.add(builder.like(
                    builder.lower(root.get("supplier").get("name")), supplierNamePattern, '!'));
            if (createdFrom != null) predicates.add(builder.greaterThanOrEqualTo(
                    root.get("createdAt"), createdFrom.atStartOfDay(zone).toInstant()));
            if (createdTo != null) predicates.add(builder.lessThan(
                    root.get("createdAt"), createdTo.plusDays(1).atStartOfDay(zone).toInstant()));
            if (expectedDeliveryFrom != null) predicates.add(builder.greaterThanOrEqualTo(
                    root.get("expectedDeliveryDate"), expectedDeliveryFrom));
            if (expectedDeliveryTo != null) predicates.add(builder.lessThanOrEqualTo(
                    root.get("expectedDeliveryDate"), expectedDeliveryTo));
            if (materialId != null || materialCodePattern != null) {
                Subquery<Long> itemExists = query.subquery(Long.class);
                var item = itemExists.from(PurchaseOrderItem.class);
                List<Predicate> itemPredicates = new ArrayList<>();
                itemPredicates.add(builder.equal(item.get("purchaseOrder"), root));
                if (materialId != null) itemPredicates.add(builder.equal(item.get("material").get("id"), materialId));
                if (materialCodePattern != null) itemPredicates.add(builder.like(
                        builder.lower(item.get("material").get("code")), materialCodePattern, '!'));
                itemExists.select(builder.literal(1L)).where(builder.and(itemPredicates.toArray(Predicate[]::new)));
                predicates.add(builder.exists(itemExists));
            }
            Predicate isOverdue = builder.and(
                    builder.equal(root.get("status"), PurchaseOrderStatus.ORDERED),
                    builder.isNotNull(root.get("expectedDeliveryDate")),
                    builder.lessThan(root.get("expectedDeliveryDate"), today));
            if (Boolean.TRUE.equals(overdue)) predicates.add(isOverdue);
            if (Boolean.FALSE.equals(overdue)) predicates.add(builder.not(isOverdue));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        Page<PurchaseOrder> result = purchaseOrderRepository.findAll(specification, pageable);
        List<Long> ids = result.getContent().stream().map(PurchaseOrder::getId).toList();
        Map<Long, PurchaseOrder> detailed = ids.isEmpty() ? Map.of() : purchaseOrderRepository
                .findDetailedByIdIn(ids).stream().collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));
        List<PurchaseOrderResponse> content = ids.stream()
                .map(detailed::get).map(purchaseOrderMapper::toResponse).toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public PageResponse<PurchaseOrderResponse> listOpen(String materialCode, Pageable pageable) {
        Long materialId = null;
        if (materialCode != null) {
            materialId = materialRepository.findByCode(materialCode)
                    .orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND))
                    .getId();
        }
        return list(PurchaseOrderStatus.ORDERED, null, materialId, null, null, null, null,
                null, null, null, null, null, pageable);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'ADMIN')")
    public PurchaseOrderResponse updateDraft(Long id, UpdatePurchaseOrderRequest request) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new BusinessException(ErrorCode.PO_NOT_EDITABLE);
        }

        Supplier finalSupplier = request.supplierIdProvided()
                ? activeSupplier(request.supplierId())
                : purchaseOrder.getSupplier();
        validateActiveSupplier(finalSupplier);

        List<PurchaseOrderItem> replacementItems = null;
        if (request.itemsProvided()) {
            HashSet<Long> materialIds = new HashSet<>();
            replacementItems = request.items().stream().map(itemRequest -> {
                if (!materialIds.add(itemRequest.materialId())) throw new BusinessException(ErrorCode.DUPLICATE_MATERIAL);
                Material material = activeMaterial(itemRequest.materialId());
                validateSupplierMaterial(finalSupplier, material);
                return PurchaseOrderItem.create(material, itemRequest.quantity(), itemRequest.unitPrice(), clock.instant());
            }).toList();
        } else {
            for (PurchaseOrderItem item : purchaseOrder.getItems()) {
                validateRetainedItem(item, finalSupplier);
            }
        }

        purchaseOrder.editDraft(
                finalSupplier,
                request.supplierIdProvided(),
                request.expectedDeliveryDate(),
                request.expectedDeliveryDateProvided(),
                replacementItems,
                request.itemsProvided(),
                clock.instant());
        PurchaseOrder persisted = purchaseOrderRepository.saveAndFlush(purchaseOrder);
        return purchaseOrderMapper.toResponse(persisted);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'ADMIN')")
    public PurchaseOrderResponse updateSupplierPromisedDeliveryDate(
            Long id, UpdateSupplierPromisedDeliveryDateRequest request) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        purchaseOrder.updateSupplierPromisedDeliveryDate(request.supplierPromisedDeliveryDate(), clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_MANAGER', 'ADMIN')")
    public PurchaseOrderResponse approve(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        User approver = currentUser();
        purchaseOrder.approve(approver, clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public PurchaseOrderResponse advanceFromNewRequest(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        purchaseOrder.advanceFromNewRequest(clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public PurchaseOrderResponse advanceFromSourcing(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        purchaseOrder.advanceFromSourcing(clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_MANAGER', 'ADMIN')")
    public PurchaseOrderResponse reject(Long id, RejectPurchaseOrderRequest request) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        String reason = request == null ? null : request.reason();
        User rejector = currentUser();
        purchaseOrder.reject(reason, rejector, clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'ADMIN')")
    public PurchaseOrderResponse markOrdered(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        purchaseOrder.markOrdered(currentUser(), clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PurchaseOrderResponse beginReceivingInspection(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        purchaseOrder.beginReceivingInspection(clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PurchaseOrderResponse recordReceivingInspection(Long id, ReceivingInspectionRequest request) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        purchaseOrder.recordReceivingInspection(request.allItemsReceived(), request.allQuantitiesConfirmed(),
                request.allQualityAccepted(), request.note(), currentUser(), clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PurchaseOrderResponse confirmPayment(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        purchaseOrder.confirmPayment(currentUser(), clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PurchaseOrderResponse revokePaymentConfirmation(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        purchaseOrder.revokePaymentConfirmation(clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PurchaseOrderResponse cancel(Long id, String reason) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        User actor = currentUser();
        purchaseOrder.cancel(reason, actor, clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PurchaseOrderResponse claim(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        User actor = userRepository.findByIdForUpdate(currentUserProvider.getCurrentUserId())
                .filter(user -> user.getStatus() == com.supplypilot.user.domain.UserStatus.ACTIVE
                        && user.getRole() == com.supplypilot.user.domain.UserRole.ADMIN)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_INACTIVE));
        if (purchaseOrder.getAssignedTo() != null) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_ALREADY_ASSIGNED);
        }
        purchaseOrder.assignTo(actor, clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public PurchaseOrderResponse assign(Long id, Long assigneeId) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        User assignee = userRepository.findByIdForUpdate(assigneeId)
                .filter(user -> user.getStatus() == com.supplypilot.user.domain.UserStatus.ACTIVE
                        && user.getRole() == com.supplypilot.user.domain.UserRole.ADMIN)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        purchaseOrder.assignTo(assignee, clock.instant());
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    private String containsPattern(String value) {
        if (value == null || value.isBlank()) return null;
        String escaped = value.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }
    }

    private User currentUser() {
        return userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private PurchaseOrderItem createItem(CreatePurchaseOrderItemRequest request, Material material, Supplier supplier) {
        return createItem(itemInput(request), material, supplier);
    }

    private PurchaseOrderItem createItem(ItemInput request, Material material, Supplier supplier) {
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

    private void validateRecommendationInputShape(ItemInput request) {
        if (request.recommendationId() == null) {
            throw new BusinessException(ErrorCode.INVALID_RECOMMENDATION_REFERENCE);
        }
        if (request.supplierRecommendationResultId() == null) {
            throw new BusinessException(ErrorCode.INVALID_SUPPLIER_RECOMMENDATION_REFERENCE);
        }
    }

    private Supplier activeSupplier(Long supplierId) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND));
        validateActiveSupplier(supplier);
        return supplier;
    }

    private void validateActiveSupplier(Supplier supplier) {
        if (supplier.getStatus() != SupplierStatus.ACTIVE) throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE);
    }

    private Material activeMaterial(Long materialId) {
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
        if (material.getStatus() != MaterialStatus.ACTIVE) throw new BusinessException(ErrorCode.MATERIAL_INACTIVE);
        return material;
    }

    private void validateSupplierMaterial(Supplier supplier, Material material) {
        SupplierMaterial supplierMaterial = supplierMaterialRepository
                .findBySupplierIdAndMaterialId(supplier.getId(), material.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_MATERIAL_NOT_FOUND));
        if (supplierMaterial.getStatus() != SupplierMaterialStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.SUPPLIER_MATERIAL_INACTIVE);
        }
    }

    private void validateRetainedItem(PurchaseOrderItem item, Supplier supplier) {
        Material material = activeMaterial(item.getMaterial().getId());
        validateSupplierMaterial(supplier, material);
    }

    private ItemInput itemInput(CreatePurchaseOrderItemRequest request) {
        return new ItemInput(request.materialId(), request.quantity(), request.unitPrice(), request.recommendationId(),
                request.supplierRecommendationResultId(), request.supplierOverrideReason(), request.quantityOverrideReason());
    }

    private String overrideReason(boolean override, String reason, ErrorCode missingReasonError) {
        if (!override) return null;
        if (reason == null || reason.trim().isEmpty()) throw new BusinessException(missingReasonError);
        return reason;
    }

    private String displayPoNumber(Long id, LocalDate date) {
        return "PO-%04d%02d%02d-%06d".formatted(date.getYear(), date.getMonthValue(), date.getDayOfMonth(), id);
    }

    private record ItemInput(
            Long materialId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            Long recommendationId,
            Long supplierRecommendationResultId,
            String supplierOverrideReason,
            String quantityOverrideReason) {
        boolean hasRecommendationInput() {
            return recommendationId != null || supplierRecommendationResultId != null
                    || supplierOverrideReason != null || quantityOverrideReason != null;
        }
    }
}
