package com.supplypilot.procurement.service;

import java.time.Clock;
import java.time.Instant;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.domain.StockTransaction;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.inventory.repository.StockTransactionRepository;
import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import com.supplypilot.procurement.mapper.PurchaseOrderMapper;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.repository.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceivingService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final InventoryRepository inventoryRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final Clock clock;

    public ReceivingService(
            PurchaseOrderRepository purchaseOrderRepository,
            InventoryRepository inventoryRepository,
            StockTransactionRepository stockTransactionRepository,
            UserRepository userRepository,
            CurrentUserProvider currentUserProvider,
            PurchaseOrderMapper purchaseOrderMapper,
            Clock clock
    ) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.inventoryRepository = inventoryRepository;
        this.stockTransactionRepository = stockTransactionRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'ADMIN')")
    public PurchaseOrderResponse receive(Long purchaseOrderId) {
        PurchaseOrder purchaseOrder = purchaseOrderRepository.findById(purchaseOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        purchaseOrder.validateReceivingEligibility();
        User receiver = userRepository.findById(currentUserProvider.getCurrentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Instant now = clock.instant();

        for (PurchaseOrderItem item : purchaseOrder.getItems()) {
            Long materialId = item.getMaterial().getId();
            Inventory inventory = inventoryRepository.findByMaterialId(materialId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_NOT_FOUND));
            inventory.receive(item.getQuantity(), now);
            inventoryRepository.save(inventory);
            stockTransactionRepository.saveAndFlush(StockTransaction.purchaseReceive(
                    item.getMaterial(), item.getQuantity(), purchaseOrder.getId(), receiver, now));
        }

        purchaseOrder.receive(receiver, now);
        return purchaseOrderMapper.toResponse(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }
}
