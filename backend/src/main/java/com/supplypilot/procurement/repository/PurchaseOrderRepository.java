package com.supplypilot.procurement.repository;

import com.supplypilot.procurement.domain.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
}
