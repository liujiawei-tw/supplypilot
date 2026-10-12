package com.supplypilot.procurement.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long>, JpaSpecificationExecutor<PurchaseOrder> {

    interface NumberProjection {
        Long getId();
        String getPoNumber();
    }

    @Query("select purchaseOrder.id as id, purchaseOrder.poNumber as poNumber from PurchaseOrder purchaseOrder where purchaseOrder.id in :ids")
    List<NumberProjection> findNumberByIdIn(@Param("ids") Collection<Long> ids);

    @Query("""
            select distinct purchaseOrder
            from PurchaseOrder purchaseOrder
            join fetch purchaseOrder.supplier
            join fetch purchaseOrder.createdBy
            left join fetch purchaseOrder.approvedBy
            left join fetch purchaseOrder.rejectedBy
            left join fetch purchaseOrder.orderedBy
            left join fetch purchaseOrder.receivedBy
            left join fetch purchaseOrder.items item
            left join fetch item.material
            where purchaseOrder.id in :ids
            """)
    List<PurchaseOrder> findDetailedByIdIn(@Param("ids") Collection<Long> ids);

    @Query("""
            select distinct purchaseOrder
            from PurchaseOrder purchaseOrder
            join fetch purchaseOrder.supplier
            left join fetch purchaseOrder.receivedBy
            left join fetch purchaseOrder.items item
            left join fetch item.material
            where purchaseOrder.id in :ids
            """)
    List<PurchaseOrder> findReceivingDetailedByIdIn(@Param("ids") Collection<Long> ids);

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
