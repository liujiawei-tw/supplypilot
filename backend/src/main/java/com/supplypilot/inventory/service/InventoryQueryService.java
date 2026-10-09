package com.supplypilot.inventory.service;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.dto.response.InventoryResponse;
import com.supplypilot.inventory.mapper.InventoryMapper;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.material.repository.MaterialRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryQueryService {
    private final InventoryRepository inventoryRepository;
    private final MaterialRepository materialRepository;
    private final InventoryMapper inventoryMapper;

    public InventoryQueryService(InventoryRepository inventoryRepository, MaterialRepository materialRepository,
                                 InventoryMapper inventoryMapper) {
        this.inventoryRepository = inventoryRepository;
        this.materialRepository = materialRepository;
        this.inventoryMapper = inventoryMapper;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PRODUCTION_PLANNER', 'PROCUREMENT_STAFF', 'WAREHOUSE_STAFF', 'ADMIN')")
    public PageResponse<InventoryResponse> listInventory(Long materialId, String materialCode, Pageable pageable) {
        Specification<Inventory> specification = (root, query, builder) -> {
            if (materialId != null && hasText(materialCode)) {
                return builder.and(
                        builder.equal(root.get("material").get("id"), materialId),
                        builder.like(root.get("material").get("code"), "%" + materialCode.trim() + "%")
                );
            }
            if (materialId != null) {
                return builder.equal(root.get("material").get("id"), materialId);
            }
            if (hasText(materialCode)) {
                return builder.like(root.get("material").get("code"), "%" + materialCode.trim() + "%");
            }
            return builder.conjunction();
        };
        Page<Inventory> page = inventoryRepository.findAll(specification, pageable);
        return PageResponse.from(page.map(inventoryMapper::toResponse));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('PRODUCTION_PLANNER', 'PROCUREMENT_STAFF', 'WAREHOUSE_STAFF', 'ADMIN')")
    public InventoryResponse getInventoryByMaterialId(Long materialId) {
        if (!materialRepository.existsById(materialId)) {
            throw new BusinessException(ErrorCode.MATERIAL_NOT_FOUND);
        }
        Inventory inventory = inventoryRepository.findByMaterialId(materialId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_NOT_FOUND));
        return inventoryMapper.toResponse(inventory);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
