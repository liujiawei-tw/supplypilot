package com.supplypilot.inventory.controller;

import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.inventory.dto.request.InventoryAdjustmentRequest;
import com.supplypilot.inventory.dto.response.InventoryResponse;
import com.supplypilot.inventory.service.InventoryAdjustmentService;
import com.supplypilot.inventory.service.InventoryQueryService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
@Validated
public class InventoryController {
    private final InventoryQueryService inventoryQueryService;
    private final InventoryAdjustmentService inventoryAdjustmentService;

    public InventoryController(InventoryQueryService inventoryQueryService,
                               InventoryAdjustmentService inventoryAdjustmentService) {
        this.inventoryQueryService = inventoryQueryService;
        this.inventoryAdjustmentService = inventoryAdjustmentService;
    }

    @GetMapping
    public PageResponse<InventoryResponse> listInventory(
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) String materialCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return inventoryQueryService.listInventory(materialId, materialCode,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id")));
    }

    @GetMapping("/{materialId}")
    public InventoryResponse getInventoryByMaterialId(@PathVariable Long materialId) {
        return inventoryQueryService.getInventoryByMaterialId(materialId);
    }

    @PostMapping("/{materialId}/adjustments")
    public void adjustInventory(@PathVariable Long materialId,
                                @Valid @RequestBody InventoryAdjustmentRequest request) {
        inventoryAdjustmentService.adjustInventory(materialId, request);
    }
}
