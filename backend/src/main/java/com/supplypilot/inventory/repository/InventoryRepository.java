package com.supplypilot.inventory.repository;

import java.util.Optional;

import com.supplypilot.inventory.domain.Inventory;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    Optional<Inventory> findByMaterialId(Long materialId);
    long countByMaterialId(Long materialId);
}
