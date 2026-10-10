package com.supplypilot.procurement.controller;

import com.supplypilot.procurement.dto.request.CreatePurchaseOrderRequest;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import com.supplypilot.procurement.service.PurchaseOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/purchase-orders")
public class PurchaseOrderController {
    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
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
}
