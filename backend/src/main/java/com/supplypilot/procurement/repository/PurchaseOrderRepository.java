package com.supplypilot.procurement.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @Query("""
            select sum(item.quantity)
            from PurchaseOrderItem item
            join item.purchaseOrder purchaseOrder
            where purchaseOrder.status = :status
              and purchaseOrder.expectedDeliveryDate is not null
              and purchaseOrder.expectedDeliveryDate <= :requiredDate
              and item.material.id = :materialId
            """)
    BigDecimal sumIncomingQuantity(
            @Param("materialId") Long materialId,
            @Param("requiredDate") LocalDate requiredDate,
            @Param("status") PurchaseOrderStatus status);
}
