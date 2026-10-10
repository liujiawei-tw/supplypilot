package com.supplypilot.procurement.query;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.repository.PurchaseOrderRepository;
import com.supplypilot.recommendation.query.IncomingInventoryQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseOrderIncomingInventoryQuery implements IncomingInventoryQuery {

    private final PurchaseOrderRepository purchaseOrderRepository;

    public PurchaseOrderIncomingInventoryQuery(PurchaseOrderRepository purchaseOrderRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateIncomingQuantity(Long materialId, LocalDate requiredDate) {
        if (materialId == null || requiredDate == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        BigDecimal quantity = purchaseOrderRepository.sumIncomingQuantity(
                materialId, requiredDate, PurchaseOrderStatus.ORDERED);
        return quantity == null ? BigDecimal.ZERO : quantity;
    }
}
