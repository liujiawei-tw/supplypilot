package com.supplypilot.inventory.importing.repository;

import com.supplypilot.inventory.importing.domain.InventoryImportBatch;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryImportBatchRepository extends JpaRepository<InventoryImportBatch, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select batch from InventoryImportBatch batch where batch.id = :id")
    Optional<InventoryImportBatch> findByIdForUpdate(@Param("id") Long id);
}
