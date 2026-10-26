package com.supplypilot.material.repository;

import com.supplypilot.material.domain.Material;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MaterialRepository extends JpaRepository<Material, Long>, JpaSpecificationExecutor<Material> {
    boolean existsByCode(String code);
    Optional<Material> findByCode(String code);
    List<Material> findByNameIgnoreCase(String name);
    List<Material> findByNameContainingIgnoreCase(String name);
}
