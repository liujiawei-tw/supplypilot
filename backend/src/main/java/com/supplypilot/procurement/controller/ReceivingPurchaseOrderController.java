package com.supplypilot.procurement.controller;

import java.time.LocalDate;
import java.util.Set;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.dto.response.ReceivingPurchaseOrderResponse;
import com.supplypilot.procurement.service.ReceivingPurchaseOrderQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/receiving/purchase-orders")
@Validated
public class ReceivingPurchaseOrderController {
    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "id", "poNumber", "expectedDeliveryDate", "orderedAt", "receivedAt");
    private final ReceivingPurchaseOrderQueryService receivingPurchaseOrderQueryService;

    public ReceivingPurchaseOrderController(ReceivingPurchaseOrderQueryService receivingPurchaseOrderQueryService) {
        this.receivingPurchaseOrderQueryService = receivingPurchaseOrderQueryService;
    }

    @GetMapping
    public PageResponse<ReceivingPurchaseOrderResponse> list(
            @RequestParam(required = false) PurchaseOrderStatus status,
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) LocalDate expectedDeliveryFrom,
            @RequestParam(required = false) LocalDate expectedDeliveryTo,
            @RequestParam(required = false) LocalDate orderedFrom,
            @RequestParam(required = false) LocalDate orderedTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "id,asc") String sort) {
        return receivingPurchaseOrderQueryService.list(status, materialId, expectedDeliveryFrom, expectedDeliveryTo,
                orderedFrom, orderedTo, PageRequest.of(page, size, parseSort(sort)));
    }

    @GetMapping("/{id}")
    public ReceivingPurchaseOrderResponse getById(@PathVariable Long id) {
        return receivingPurchaseOrderQueryService.getById(id);
    }

    private Sort parseSort(String sort) {
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || parts[0].isBlank() || !SORTABLE_FIELDS.contains(parts[0])
                || (parts.length == 2 && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc"))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Sort.Direction direction = parts.length == 2 && parts[1].equalsIgnoreCase("desc")
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort requested = Sort.by(direction, parts[0]);
        return "id".equals(parts[0]) ? requested : requested.and(Sort.by(Sort.Direction.ASC, "id"));
    }
}
