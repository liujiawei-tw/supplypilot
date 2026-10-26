package com.supplypilot.supplier.repository;

import java.util.Optional;
import java.util.List;

import com.supplypilot.supplier.domain.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierRepository extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {
    boolean existsByCode(String code);
    Optional<Supplier> findByCode(String code);
    List<Supplier> findByNameIgnoreCase(String name);
    List<Supplier> findByNameContainingIgnoreCase(String name);
}
