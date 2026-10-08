package com.supplypilot.material.controller;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.dto.request.CreateMaterialRequest;
import com.supplypilot.material.dto.request.UpdateMaterialRequest;
import com.supplypilot.material.dto.response.MaterialResponse;
import com.supplypilot.material.service.MaterialService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/materials")
@Validated
public class MaterialController {
    private static final java.util.Set<String> SORTABLE_FIELDS = java.util.Set.of("id", "code", "name", "baseUnit", "safetyStock", "status", "createdAt", "updatedAt");
    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialResponse createMaterial(@Valid @RequestBody CreateMaterialRequest request) {
        return materialService.createMaterial(request);
    }

    @GetMapping
    public PageResponse<MaterialResponse> listMaterials(
            @RequestParam(required = false) MaterialStatus status,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "id,asc") String sort
    ) {
        return materialService.listMaterials(status, code, name, PageRequest.of(page, size, parseSort(sort)));
    }

    @GetMapping("/{id}")
    public MaterialResponse getMaterialById(@PathVariable Long id) {
        return materialService.getMaterialById(id);
    }

    @PatchMapping("/{id}")
    public MaterialResponse updateMaterial(@PathVariable Long id, @Valid @RequestBody UpdateMaterialRequest request) {
        return materialService.updateMaterial(id, request);
    }

    private Sort parseSort(String sort) {
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || parts[0].isBlank() || !SORTABLE_FIELDS.contains(parts[0])
                || (parts.length == 2 && !parts[1].equalsIgnoreCase("asc") && !parts[1].equalsIgnoreCase("desc"))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return Sort.by(parts.length == 2 && parts[1].equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC, parts[0]);
    }
}
