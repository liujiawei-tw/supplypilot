package com.supplypilot.inventory.repository;

import com.supplypilot.inventory.domain.StockTransaction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockTransactionRepository extends JpaRepository<StockTransaction, Long> {
}
