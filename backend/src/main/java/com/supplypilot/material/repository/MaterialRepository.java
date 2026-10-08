package com.supplypilot.material.repository;

import com.supplypilot.material.domain.Material;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MaterialRepository extends JpaRepository<Material, Long>, JpaSpecificationExecutor<Material> {
    boolean existsByCode(String code);
}
