package com.supplypilot.supplier.repository;

import java.util.Optional;

import com.supplypilot.supplier.domain.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SupplierRepository extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {
    boolean existsByCode(String code);
    Optional<Supplier> findByCode(String code);
}
