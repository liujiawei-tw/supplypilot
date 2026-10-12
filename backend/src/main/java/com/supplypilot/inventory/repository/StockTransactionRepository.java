package com.supplypilot.inventory.repository;

import com.supplypilot.inventory.domain.StockTransaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockTransactionRepository extends JpaRepository<StockTransaction, Long>, JpaSpecificationExecutor<StockTransaction> {

    @Override
    @EntityGraph(attributePaths = {"material", "createdBy"})
    Page<StockTransaction> findAll(org.springframework.data.jpa.domain.Specification<StockTransaction> specification, Pageable pageable);

    @EntityGraph(attributePaths = {"material", "createdBy"})
    java.util.Optional<StockTransaction> findById(Long id);
}
