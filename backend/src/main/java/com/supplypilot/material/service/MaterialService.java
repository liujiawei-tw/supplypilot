package com.supplypilot.material.service;

import java.time.Clock;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.domain.MaterialStatus;
import com.supplypilot.material.dto.request.CreateMaterialRequest;
import com.supplypilot.material.dto.request.UpdateMaterialRequest;
import com.supplypilot.material.dto.response.MaterialResponse;
import com.supplypilot.material.mapper.MaterialMapper;
import com.supplypilot.material.repository.MaterialRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialService {

    private final MaterialRepository materialRepository;
    private final InventoryRepository inventoryRepository;
    private final MaterialMapper materialMapper;
    private final Clock clock;

    public MaterialService(MaterialRepository materialRepository, InventoryRepository inventoryRepository,
                           MaterialMapper materialMapper, Clock clock) {
        this.materialRepository = materialRepository;
        this.inventoryRepository = inventoryRepository;
        this.materialMapper = materialMapper;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public MaterialResponse createMaterial(CreateMaterialRequest request) {
        String code = request.code().trim();
        if (materialRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.DUPLICATE_CODE);
        }

        Material material = Material.create(code, request.name(), request.baseUnit(), request.safetyStock(), clock.instant());
        try {
            Material savedMaterial = materialRepository.saveAndFlush(material);
            inventoryRepository.saveAndFlush(Inventory.create(savedMaterial, clock.instant()));
            return materialMapper.toResponse(savedMaterial);
        } catch (DataIntegrityViolationException exception) {
            if (isMaterialCodeUniqueConstraintViolation(exception)) {
                throw new BusinessException(ErrorCode.DUPLICATE_CODE);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<MaterialResponse> listMaterials(MaterialStatus status, String code, String name, Pageable pageable) {
        Specification<Material> specification = Specification.unrestricted();
        if (status != null) {
            specification = specification.and(
                    (root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (hasText(code)) {
            specification = specification.and(
                    (root, query, builder) -> builder.like(root.get("code"), "%" + code.trim() + "%"));
        }
        if (hasText(name)) {
            specification = specification.and(
                    (root, query, builder) -> builder.like(root.get("name"), "%" + name.trim() + "%"));
        }
        Page<Material> page = materialRepository.findAll(specification, pageable);
        return PageResponse.from(page.map(materialMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public boolean existsByCode(String code) {
        return code != null && materialRepository.existsByCode(code);
    }

    @Transactional(readOnly = true)
    public MaterialResponse getMaterialById(Long id) {
        return materialMapper.toResponse(findMaterial(id));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public MaterialResponse updateMaterial(Long id, UpdateMaterialRequest request) {
        Material material = findMaterial(id);
        material.update(request.name(), request.safetyStock(), request.status(), clock.instant());
        return materialMapper.toResponse(materialRepository.save(material));
    }

    private Material findMaterial(Long id) {
        return materialRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
    }

    private boolean isMaterialCodeUniqueConstraintViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception.getCause();
        return cause instanceof org.hibernate.exception.ConstraintViolationException constraintViolation
                && "uk_materials_code".equalsIgnoreCase(constraintViolation.getConstraintName());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
