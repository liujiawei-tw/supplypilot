package com.supplypilot.inventory.service;

import java.time.Clock;
import java.time.Instant;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.domain.StockTransaction;
import com.supplypilot.inventory.dto.request.InventoryAdjustmentRequest;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.inventory.repository.StockTransactionRepository;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.repository.UserRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryAdjustmentService {

    private final MaterialRepository materialRepository;
    private final InventoryRepository inventoryRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public InventoryAdjustmentService(
            MaterialRepository materialRepository,
            InventoryRepository inventoryRepository,
            StockTransactionRepository stockTransactionRepository,
            UserRepository userRepository,
            CurrentUserProvider currentUserProvider,
            Clock clock
    ) {
        this.materialRepository = materialRepository;
        this.inventoryRepository = inventoryRepository;
        this.stockTransactionRepository = stockTransactionRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'ADMIN')")
    public void adjustInventory(Long materialId, InventoryAdjustmentRequest request) {
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
        Inventory inventory = inventoryRepository.findByMaterialId(materialId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_NOT_FOUND));
        User currentUser = userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Instant now = clock.instant();
        inventory.adjust(request.quantity(), now);
        inventoryRepository.save(inventory);
        stockTransactionRepository.saveAndFlush(
                StockTransaction.manualAdjustment(material, request.quantity(), request.reason(), currentUser, now)
        );
    }
}
