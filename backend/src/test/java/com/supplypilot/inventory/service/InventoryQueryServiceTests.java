package com.supplypilot.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.mapper.InventoryMapper;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryQueryServiceTests {
    @Mock private InventoryRepository inventoryRepository;
    @Mock private MaterialRepository materialRepository;
    private InventoryQueryService service;

    @BeforeEach
    void setUp() {
        service = new InventoryQueryService(inventoryRepository, materialRepository, new InventoryMapper());
    }

    @Test
    void distinguishesUnknownMaterialFromMissingInventory() {
        when(materialRepository.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> service.getInventoryByMaterialId(99L))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.MATERIAL_NOT_FOUND);

        when(materialRepository.existsById(1L)).thenReturn(true);
        when(inventoryRepository.findByMaterialId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getInventoryByMaterialId(1L))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVENTORY_NOT_FOUND);
    }

    @Test
    void mapsExistingInventoryToResponse() {
        Material material = Material.create("MAT-001", "Strawberry Concentrate", BaseUnit.KG, BigDecimal.ZERO, Instant.parse("2026-10-08T00:00:00Z"));
        Inventory inventory = Inventory.create(material, Instant.parse("2026-10-08T01:00:00Z"));
        when(materialRepository.existsById(1L)).thenReturn(true);
        when(inventoryRepository.findByMaterialId(1L)).thenReturn(Optional.of(inventory));

        var response = service.getInventoryByMaterialId(1L);

        org.assertj.core.api.Assertions.assertThat(response.materialCode()).isEqualTo("MAT-001");
        org.assertj.core.api.Assertions.assertThat(response.materialName()).isEqualTo("Strawberry Concentrate");
        org.assertj.core.api.Assertions.assertThat(response.quantity()).isEqualByComparingTo("0");
        org.assertj.core.api.Assertions.assertThat(response.unit()).isEqualTo(BaseUnit.KG);
        org.assertj.core.api.Assertions.assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-10-08T01:00:00Z"));
    }

    @Test
    void queriesInventoryByExactMaterialCodeForAiReadBoundary() {
        Material material = Material.create("MAT-001", "Strawberry Concentrate", BaseUnit.KG,
                BigDecimal.ZERO, Instant.parse("2026-10-08T00:00:00Z"));
        Inventory inventory = Inventory.create(material, Instant.parse("2026-10-08T01:00:00Z"));
        when(materialRepository.findByCode("MAT-001")).thenReturn(Optional.of(material));
        when(inventoryRepository.findByMaterialId(material.getId())).thenReturn(Optional.of(inventory));

        org.assertj.core.api.Assertions.assertThat(service.getInventoryByMaterialCode("MAT-001").materialCode())
                .isEqualTo("MAT-001");
    }
}
