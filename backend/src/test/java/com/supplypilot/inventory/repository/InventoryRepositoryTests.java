package com.supplypilot.inventory.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.supplypilot.integration.AbstractIntegrationTest;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryRepositoryTests extends AbstractIntegrationTest {
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private MaterialRepository materialRepository;

    @Test
    void filtersByMaterialIdAndMaterialCodeAndPaginatesInDatabase() {
        String prefix = "INV-" + UUID.randomUUID().toString().substring(0, 8);
        Material mat001 = material(prefix + "-MAT-001");
        Material mat002 = material(prefix + "-MAT-002");
        material(prefix + "-RAW-001");

        Page<Inventory> byMaterialId = inventoryRepository.findAll(
                (root, query, builder) -> builder.equal(root.get("material").get("id"), mat001.getId()), PageRequest.of(0, 20));
        Page<Inventory> byMaterialCode = inventoryRepository.findAll(
                (root, query, builder) -> builder.like(root.get("material").get("code"), prefix + "-MAT-00%"), PageRequest.of(0, 20));
        Page<Inventory> paged = inventoryRepository.findAll(
                (root, query, builder) -> builder.like(root.get("material").get("code"), prefix + "-%"), PageRequest.of(1, 1));
        Page<Inventory> empty = inventoryRepository.findAll(
                (root, query, builder) -> builder.equal(root.get("material").get("id"), 999999L), PageRequest.of(0, 20));

        assertThat(byMaterialId.getContent()).extracting(inventory -> inventory.getMaterial().getId()).containsExactly(mat001.getId());
        assertThat(byMaterialCode.getContent()).extracting(inventory -> inventory.getMaterial().getCode()).containsExactlyInAnyOrder(prefix + "-MAT-001", prefix + "-MAT-002");
        assertThat(paged.getTotalElements()).isEqualTo(3);
        assertThat(paged.getTotalPages()).isEqualTo(3);
        assertThat(paged.getContent()).hasSize(1);
        assertThat(empty).isEmpty();
    }

    private Material material(String code) {
        Material material = materialRepository.saveAndFlush(Material.create(code, code + " name", BaseUnit.KG, BigDecimal.ZERO, Instant.parse("2026-10-08T00:00:00Z")));
        inventoryRepository.saveAndFlush(Inventory.create(material, Instant.parse("2026-10-08T00:00:00Z")));
        return material;
    }
}
