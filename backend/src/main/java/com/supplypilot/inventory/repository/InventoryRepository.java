package com.supplypilot.inventory.repository;

import java.util.Optional;

import com.supplypilot.inventory.domain.Inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InventoryRepository extends JpaRepository<Inventory, Long>, JpaSpecificationExecutor<Inventory> {
    @Override
    @EntityGraph(attributePaths = "material")
    Page<Inventory> findAll(Specification<Inventory> specification, Pageable pageable);

    @EntityGraph(attributePaths = "material")
    Optional<Inventory> findByMaterialId(Long materialId);
    long countByMaterialId(Long materialId);
}
