package com.supplypilot.inventory.importing.repository;

import java.util.List;

import com.supplypilot.inventory.importing.domain.InventoryImportItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryImportItemRepository extends JpaRepository<InventoryImportItem, Long> {
    List<InventoryImportItem> findByBatchIdOrderByRowNumber(Long batchId);
}
