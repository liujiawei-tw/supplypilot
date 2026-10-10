package com.supplypilot.supplier.repository;

import com.supplypilot.supplier.domain.SupplierMaterial;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;

public interface SupplierMaterialRepository extends JpaRepository<SupplierMaterial, Long>, JpaSpecificationExecutor<SupplierMaterial> {
    boolean existsBySupplierIdAndMaterialId(Long supplierId, Long materialId);
    Optional<SupplierMaterial> findBySupplierIdAndMaterialId(Long supplierId, Long materialId);

    @EntityGraph(attributePaths = {"supplier", "material"})
    List<SupplierMaterial> findByMaterialIdOrderByIdAsc(Long materialId);
}
