package com.supplypilot.supplier.controller;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.dto.request.CreateSupplierMaterialRequest;
import com.supplypilot.supplier.dto.request.UpdateSupplierMaterialRequest;
import com.supplypilot.supplier.dto.response.SupplierMaterialResponse;
import com.supplypilot.supplier.service.SupplierMaterialService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/supplier-materials") @Validated
public class SupplierMaterialController {
    private static final java.util.Set<String> SORTABLE_FIELDS = java.util.Set.of("id", "unitPrice", "moq", "packageMultiple", "leadTimeDays", "status", "createdAt", "updatedAt");
    private final SupplierMaterialService service;
    public SupplierMaterialController(SupplierMaterialService service) { this.service = service; }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public SupplierMaterialResponse create(@Valid @RequestBody CreateSupplierMaterialRequest request) { return service.createSupplierMaterial(request); }
    @GetMapping public PageResponse<SupplierMaterialResponse> list(@RequestParam(required = false) Long supplierId, @RequestParam(required = false) Long materialId, @RequestParam(required = false) SupplierMaterialStatus status, @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, @RequestParam(defaultValue = "id,asc") String sort) { return service.listSupplierMaterials(supplierId, materialId, status, PageRequest.of(page, size, parseSort(sort))); }
    @GetMapping("/{id}") public SupplierMaterialResponse get(@PathVariable Long id) { return service.getSupplierMaterialById(id); }
    @PatchMapping("/{id}") public SupplierMaterialResponse update(@PathVariable Long id, @Valid @RequestBody UpdateSupplierMaterialRequest request) { return service.updateSupplierMaterial(id, request); }
    private Sort parseSort(String sort) { String[] parts = sort.split(",", -1); if (parts.length > 2 || parts[0].isBlank() || !SORTABLE_FIELDS.contains(parts[0]) || (parts.length == 2 && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc"))) throw new BusinessException(ErrorCode.VALIDATION_FAILED); return Sort.by(parts.length == 2 && parts[1].equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC, parts[0]); }
}
