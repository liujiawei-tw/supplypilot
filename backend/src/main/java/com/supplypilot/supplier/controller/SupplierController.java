package com.supplypilot.supplier.controller;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.supplier.domain.SupplierStatus;
import com.supplypilot.supplier.dto.request.CreateSupplierRequest;
import com.supplypilot.supplier.dto.request.UpdateSupplierRequest;
import com.supplypilot.supplier.dto.response.SupplierResponse;
import com.supplypilot.supplier.service.SupplierService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/suppliers") @Validated
public class SupplierController {
    private static final java.util.Set<String> SORTABLE_FIELDS = java.util.Set.of("id", "code", "name", "country", "reliabilityScore", "qualityScore", "status", "createdAt", "updatedAt");
    private final SupplierService supplierService;
    public SupplierController(SupplierService supplierService) { this.supplierService = supplierService; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public SupplierResponse createSupplier(@Valid @RequestBody CreateSupplierRequest request) { return supplierService.createSupplier(request); }
    @GetMapping
    public PageResponse<SupplierResponse> listSuppliers(@RequestParam(required=false) SupplierStatus status, @RequestParam(required=false) String code,
            @RequestParam(required=false) String name, @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size, @RequestParam(defaultValue="id,asc") String sort) {
        return supplierService.listSuppliers(status, code, name, PageRequest.of(page, size, parseSort(sort)));
    }
    @GetMapping("/{id}") public SupplierResponse getSupplierById(@PathVariable Long id) { return supplierService.getSupplierById(id); }
    @PatchMapping("/{id}") public SupplierResponse updateSupplier(@PathVariable Long id, @Valid @RequestBody UpdateSupplierRequest request) { return supplierService.updateSupplier(id, request); }
    private Sort parseSort(String sort) {
        String[] parts=sort.split(",", -1);
        if (parts.length>2 || parts[0].isBlank() || !SORTABLE_FIELDS.contains(parts[0]) || (parts.length==2 && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc"))) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return Sort.by(parts.length==2 && parts[1].equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC, parts[0]);
    }
}
