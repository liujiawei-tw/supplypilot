package com.supplypilot.inventory.mapper;

import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.dto.response.InventoryResponse;

import org.springframework.stereotype.Component;

@Component
public class InventoryMapper {
    public InventoryResponse toResponse(Inventory inventory) {
        return new InventoryResponse(
                inventory.getMaterial().getId(),
                inventory.getMaterial().getCode(),
                inventory.getMaterial().getName(),
                inventory.getQuantity(),
                inventory.getMaterial().getBaseUnit(),
                inventory.getUpdatedAt()
        );
    }
}
