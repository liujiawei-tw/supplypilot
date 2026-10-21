package com.supplypilot.inventory.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.inventory.domain.StockTransaction;
import com.supplypilot.inventory.domain.StockTransactionType;
import com.supplypilot.inventory.dto.response.StockTransactionReadResponse;
import com.supplypilot.inventory.repository.StockTransactionRepository;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockTransactionQueryService {
    private final StockTransactionRepository transactionRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;

    public StockTransactionQueryService(StockTransactionRepository transactionRepository,
                                        PurchaseOrderRepository purchaseOrderRepository) {
        this.transactionRepository = transactionRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public PageResponse<StockTransactionReadResponse> list(Long materialId, StockTransactionType type,
                                                             Long purchaseOrderId, Instant from, Instant to,
                                                             Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Specification<StockTransaction> specification = (root, query, builder) -> {
            var predicate = builder.conjunction();
            if (materialId != null) predicate = builder.and(predicate, builder.equal(root.get("material").get("id"), materialId));
            if (type != null) predicate = builder.and(predicate, builder.equal(root.get("type"), type));
            if (purchaseOrderId != null) predicate = builder.and(predicate, builder.equal(root.get("purchaseOrderId"), purchaseOrderId));
            if (from != null) predicate = builder.and(predicate, builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicate = builder.and(predicate, builder.lessThanOrEqualTo(root.get("createdAt"), to));
            return predicate;
        };
        Page<StockTransaction> page = transactionRepository.findAll(specification, pageable);
        Map<Long, String> numbers = numbersFor(page.getContent());
        return PageResponse.from(page.map(transaction -> toResponse(transaction, numbers)));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public StockTransactionReadResponse get(Long id) {
        StockTransaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_TRANSACTION_NOT_FOUND));
        return toResponse(transaction, numbersFor(java.util.List.of(transaction)));
    }

    private Map<Long, String> numbersFor(Collection<StockTransaction> transactions) {
        var ids = transactions.stream().map(StockTransaction::getPurchaseOrderId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return purchaseOrderRepository.findNumberByIdIn(ids).stream()
                .collect(Collectors.toMap(PurchaseOrderRepository.NumberProjection::getId,
                        PurchaseOrderRepository.NumberProjection::getPoNumber));
    }

    private StockTransactionReadResponse toResponse(StockTransaction transaction, Map<Long, String> numbers) {
        var material = transaction.getMaterial();
        String purchaseOrderNumber = transaction.getPurchaseOrderId() == null
                ? null
                : numbers.get(transaction.getPurchaseOrderId());
        return new StockTransactionReadResponse(transaction.getId(), material.getId(), material.getCode(), material.getName(),
                material.getBaseUnit(), transaction.getType(), transaction.getQuantity(), transaction.getPurchaseOrderId(),
                purchaseOrderNumber, transaction.getReason(), transaction.getCreatedByUserIdSnapshot(),
                transaction.getCreatedByDisplayNameSnapshot(), transaction.getCreatedAt());
    }
}
