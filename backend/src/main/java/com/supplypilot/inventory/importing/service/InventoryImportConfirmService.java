package com.supplypilot.inventory.importing.service;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.importing.domain.InventoryImportBatch;
import com.supplypilot.inventory.importing.domain.InventoryImportItem;
import com.supplypilot.inventory.importing.repository.InventoryImportBatchRepository;
import com.supplypilot.inventory.importing.repository.InventoryImportItemRepository;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryImportConfirmService {
    private final InventoryImportBatchRepository batchRepository;
    private final InventoryImportItemRepository itemRepository;
    private final MaterialRepository materialRepository;
    private final InventoryRepository inventoryRepository;
    private final Clock clock;

    public InventoryImportConfirmService(InventoryImportBatchRepository batchRepository, InventoryImportItemRepository itemRepository,
            MaterialRepository materialRepository, InventoryRepository inventoryRepository, Clock clock) {
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.materialRepository = materialRepository;
        this.inventoryRepository = inventoryRepository;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'ADMIN')")
    public void confirm(Long batchId) {
        InventoryImportBatch batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_IMPORT_BATCH_NOT_FOUND));
        List<InventoryImportItem> items = itemRepository.findByBatchIdOrderByRowNumber(batchId);
        validatePersistedPreview(batch, items);
        for (InventoryImportItem item : items) {
            Material material = materialRepository.findById(item.getMaterial().getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
            if (!material.getBaseUnit().name().equals(item.getUnit())) {
                throw new BusinessException(ErrorCode.UNIT_MISMATCH);
            }
            Inventory inventory = inventoryRepository.findByMaterialId(material.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_NOT_FOUND));
            inventory.replaceSnapshot(item.getQuantity(), clock.instant());
            inventoryRepository.save(inventory);
        }
        batch.confirmImported();
        batchRepository.save(batch);
        batchRepository.flush();
    }

    private void validatePersistedPreview(InventoryImportBatch batch, List<InventoryImportItem> items) {
        if (batch.getStatus() == com.supplypilot.inventory.importing.domain.InventoryImportStatus.IMPORTED) {
            throw new BusinessException(ErrorCode.INVENTORY_IMPORT_ALREADY_CONFIRMED);
        }
        if (batch.getStatus() != com.supplypilot.inventory.importing.domain.InventoryImportStatus.VALIDATED) {
            throw new BusinessException(ErrorCode.INVENTORY_IMPORT_NOT_VALIDATED);
        }
        if (items.isEmpty() || items.size() != batch.getRowCount()) {
            throw new BusinessException(ErrorCode.INVENTORY_IMPORT_NOT_VALIDATED);
        }
        Set<Long> materialIds = new HashSet<>();
        for (InventoryImportItem item : items) {
            if (!item.isValid() || item.getErrorCode() != null || item.getErrorMessage() != null || item.getBatch() == null
                    || !batch.getId().equals(item.getBatch().getId()) || item.getMaterial() == null
                    || item.getQuantity() == null || item.getQuantity().signum() < 0 || item.getUnit() == null || item.getUnit().isBlank()
                    || !materialIds.add(item.getMaterial().getId())) {
                throw new BusinessException(ErrorCode.INVENTORY_IMPORT_NOT_VALIDATED);
            }
        }
    }
}
