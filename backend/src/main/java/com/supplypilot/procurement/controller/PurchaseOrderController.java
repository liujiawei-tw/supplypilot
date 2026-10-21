package com.supplypilot.procurement.controller;

import java.time.LocalDate;
import java.util.Set;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.dto.request.CreatePurchaseOrderRequest;
import com.supplypilot.procurement.dto.request.AssignPurchaseOrderRequest;
import com.supplypilot.procurement.dto.request.RejectPurchaseOrderRequest;
import com.supplypilot.procurement.dto.request.UpdatePurchaseOrderRequest;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import com.supplypilot.procurement.dto.response.ReceivingPurchaseOrderResponse;
import com.supplypilot.procurement.service.PurchaseOrderService;
import com.supplypilot.procurement.service.ReceivingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1/purchase-orders")
@Validated
public class PurchaseOrderController {
    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "id", "poNumber", "status", "expectedDeliveryDate", "createdAt", "updatedAt", "orderedAt");
    private final PurchaseOrderService purchaseOrderService;
    private final ReceivingService receivingService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService, ReceivingService receivingService) {
        this.purchaseOrderService = purchaseOrderService;
        this.receivingService = receivingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseOrderResponse createDraft(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        return purchaseOrderService.createDraft(request);
    }

    @GetMapping("/{id}")
    public PurchaseOrderResponse getById(@PathVariable Long id) {
        return purchaseOrderService.getById(id);
    }

    @GetMapping
    public PageResponse<PurchaseOrderResponse> list(
            @RequestParam(required = false) PurchaseOrderStatus status,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) LocalDate createdFrom,
            @RequestParam(required = false) LocalDate createdTo,
            @RequestParam(required = false) LocalDate expectedDeliveryFrom,
            @RequestParam(required = false) LocalDate expectedDeliveryTo,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "id,asc") String sort) {
        return purchaseOrderService.list(status, supplierId, materialId, createdFrom, createdTo,
                expectedDeliveryFrom, expectedDeliveryTo, overdue, PageRequest.of(page, size, parseSort(sort)));
    }

    @PatchMapping("/{id}")
    public PurchaseOrderResponse updateDraft(@PathVariable Long id, @Valid @RequestBody UpdatePurchaseOrderRequest request) {
        return purchaseOrderService.updateDraft(id, request);
    }

    @PostMapping("/{id}/approve")
    public PurchaseOrderResponse approve(@PathVariable Long id) {
        return purchaseOrderService.approve(id);
    }

    @PostMapping("/{id}/reject")
    public PurchaseOrderResponse reject(
            @PathVariable Long id,
            @RequestBody(required = false) RejectPurchaseOrderRequest request) {
        return purchaseOrderService.reject(id, request);
    }

    @PostMapping("/{id}/order")
    public PurchaseOrderResponse order(@PathVariable Long id) {
        return purchaseOrderService.markOrdered(id);
    }

    @PostMapping("/{id}/claim")
    public PurchaseOrderResponse claim(@PathVariable Long id) {
        return purchaseOrderService.claim(id);
    }

    @PostMapping("/{id}/assign")
    public PurchaseOrderResponse assign(@PathVariable Long id, @Valid @RequestBody AssignPurchaseOrderRequest request) {
        return purchaseOrderService.assign(id, request.userId());
    }

    @PostMapping("/{id}/receive")
    public ReceivingPurchaseOrderResponse receive(@PathVariable Long id) {
        return receivingService.receive(id);
    }

    private Sort parseSort(String sort) {
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || parts[0].isBlank() || !SORTABLE_FIELDS.contains(parts[0])
                || (parts.length == 2 && !parts[1].equalsIgnoreCase("asc")
                && !parts[1].equalsIgnoreCase("desc"))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Sort.Direction direction = parts.length == 2 && parts[1].equalsIgnoreCase("desc")
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort requested = Sort.by(direction, parts[0]);
        return "id".equals(parts[0]) ? requested : requested.and(Sort.by(Sort.Direction.ASC, "id"));
    }
}
