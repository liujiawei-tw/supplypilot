package com.supplypilot.inventory.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.supplypilot.inventory.domain.Inventory;
import com.supplypilot.inventory.domain.StockTransaction;
import com.supplypilot.inventory.dto.request.InventoryAdjustmentRequest;
import com.supplypilot.inventory.repository.InventoryRepository;
import com.supplypilot.inventory.repository.StockTransactionRepository;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import com.supplypilot.security.CurrentUserProvider;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryAdjustmentServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Mock private MaterialRepository materialRepository;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private StockTransactionRepository stockTransactionRepository;
    @Mock private UserRepository userRepository;
    @Mock private CurrentUserProvider currentUserProvider;

    private InventoryAdjustmentService service;
    private Material material;
    private Inventory inventory;
    private User actor;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new InventoryAdjustmentService(materialRepository, inventoryRepository, stockTransactionRepository,
                userRepository, currentUserProvider, clock);
        material = Material.create("MAT-001", "Material", BaseUnit.KG, BigDecimal.ZERO, NOW);
        inventory = Inventory.create(material, NOW);
        inventory.adjust(new BigDecimal("100"), NOW);
        actor = User.create("warehouse", "hash", "Warehouse", UserRole.WAREHOUSE_STAFF, NOW);
        when(materialRepository.findById(1L)).thenReturn(Optional.of(material));
        when(inventoryRepository.findByMaterialId(1L)).thenReturn(Optional.of(inventory));
        when(currentUserProvider.getCurrentUserId()).thenReturn(9L);
        when(userRepository.findById(9L)).thenReturn(Optional.of(actor));
    }

    @Test
    void updatesInventoryAndPersistsSingleManualAdjustmentFromTrustedActor() {
        service.adjustInventory(1L, new InventoryAdjustmentRequest(new BigDecimal("-30"), "Damaged inventory"));

        ArgumentCaptor<StockTransaction> transactionCaptor = ArgumentCaptor.forClass(StockTransaction.class);
        verify(inventoryRepository).save(inventory);
        verify(stockTransactionRepository).saveAndFlush(transactionCaptor.capture());
        assertThat(inventory.getQuantity()).isEqualByComparingTo("70.0000");
        assertThat(transactionCaptor.getValue().getCreatedBy()).isSameAs(actor);
        assertThat(transactionCaptor.getValue().getQuantity()).isEqualByComparingTo("-30.0000");
        assertThat(transactionCaptor.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void propagatesAuditWriteFailureSoTheTransactionalBoundaryCanRollback() {
        when(stockTransactionRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(StockTransaction.class)))
                .thenThrow(new IllegalStateException("audit write failed"));

        assertThatThrownBy(() -> service.adjustInventory(1L, new InventoryAdjustmentRequest(BigDecimal.ONE, "Correction")))
                .isInstanceOf(IllegalStateException.class);
    }
}
