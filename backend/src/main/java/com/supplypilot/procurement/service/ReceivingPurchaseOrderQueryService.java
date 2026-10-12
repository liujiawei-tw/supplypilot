package com.supplypilot.procurement.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.dto.response.ReceivingPurchaseOrderResponse;
import com.supplypilot.procurement.mapper.ReceivingPurchaseOrderMapper;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceivingPurchaseOrderQueryService {
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ReceivingPurchaseOrderMapper receivingPurchaseOrderMapper;
    private final Clock clock;

    public ReceivingPurchaseOrderQueryService(PurchaseOrderRepository purchaseOrderRepository,
                                              ReceivingPurchaseOrderMapper receivingPurchaseOrderMapper, Clock clock) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.receivingPurchaseOrderMapper = receivingPurchaseOrderMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'ADMIN')")
    public PageResponse<ReceivingPurchaseOrderResponse> list(PurchaseOrderStatus status, Long materialId,
                                                               LocalDate expectedDeliveryFrom, LocalDate expectedDeliveryTo,
                                                               LocalDate orderedFrom, LocalDate orderedTo, Pageable pageable) {
        validateRange(expectedDeliveryFrom, expectedDeliveryTo);
        validateRange(orderedFrom, orderedTo);
        PurchaseOrderStatus receivingStatus = validateReceivingStatus(status);
        ZoneId zone = clock.getZone();
        Specification<PurchaseOrder> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("status"), receivingStatus));
            if (expectedDeliveryFrom != null) predicates.add(builder.greaterThanOrEqualTo(
                    root.get("expectedDeliveryDate"), expectedDeliveryFrom));
            if (expectedDeliveryTo != null) predicates.add(builder.lessThanOrEqualTo(
                    root.get("expectedDeliveryDate"), expectedDeliveryTo));
            if (orderedFrom != null) predicates.add(builder.greaterThanOrEqualTo(root.get("orderedAt"), startOfDay(orderedFrom, zone)));
            if (orderedTo != null) predicates.add(builder.lessThan(root.get("orderedAt"), startOfDay(orderedTo.plusDays(1), zone)));
            if (materialId != null) {
                Subquery<Long> itemExists = query.subquery(Long.class);
                var item = itemExists.from(PurchaseOrderItem.class);
                itemExists.select(builder.literal(1L)).where(
                        builder.equal(item.get("purchaseOrder"), root),
                        builder.equal(item.get("material").get("id"), materialId));
                predicates.add(builder.exists(itemExists));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        Page<PurchaseOrder> page = purchaseOrderRepository.findAll(specification, pageable);
        List<Long> ids = page.getContent().stream().map(PurchaseOrder::getId).toList();
        Map<Long, PurchaseOrder> detailed = ids.isEmpty() ? Map.of() : purchaseOrderRepository
                .findReceivingDetailedByIdIn(ids).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));
        List<ReceivingPurchaseOrderResponse> content = ids.stream()
                .map(detailed::get).map(receivingPurchaseOrderMapper::toResponse).toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'ADMIN')")
    public ReceivingPurchaseOrderResponse getById(Long id) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(id)
                .filter(this::isReceivingVisible)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        return purchaseOrderRepository.findReceivingDetailedByIdIn(List.of(purchaseOrder.getId())).stream().findFirst()
                .map(receivingPurchaseOrderMapper::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
    }

    private PurchaseOrderStatus validateReceivingStatus(PurchaseOrderStatus status) {
        PurchaseOrderStatus requested = status == null ? PurchaseOrderStatus.ORDERED : status;
        if (!isReceivingVisible(requested)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return requested;
    }

    private boolean isReceivingVisible(PurchaseOrder purchaseOrder) {
        return isReceivingVisible(purchaseOrder.getStatus());
    }

    private boolean isReceivingVisible(PurchaseOrderStatus status) {
        return status == PurchaseOrderStatus.ORDERED || status == PurchaseOrderStatus.RECEIVED;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private Instant startOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }
}
