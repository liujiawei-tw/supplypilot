package com.supplypilot.inventory.importing.repository;

import com.supplypilot.inventory.importing.domain.InventoryImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryImportBatchRepository extends JpaRepository<InventoryImportBatch, Long> {
}
