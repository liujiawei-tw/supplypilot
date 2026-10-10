package com.supplypilot.supplier.repository;

import com.supplypilot.supplier.domain.SupplierMaterial;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierMaterialRepository extends JpaRepository<SupplierMaterial, Long>, JpaSpecificationExecutor<SupplierMaterial> {
    boolean existsBySupplierIdAndMaterialId(Long supplierId, Long materialId);
    Optional<SupplierMaterial> findBySupplierIdAndMaterialId(Long supplierId, Long materialId);
}
