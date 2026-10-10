package com.supplypilot.recommendation.ranking;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.Material;
import com.supplypilot.recommendation.domain.DisqualificationReason;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.supplier.domain.SupplierMaterial;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import com.supplypilot.supplier.domain.SupplierStatus;
import java.time.LocalDate;

public class SupplierConstraintEvaluator {

    public SupplierConstraintEvaluation evaluate(
            Supplier supplier,
            SupplierMaterial supplierMaterial,
            Material requiredMaterial,
            LocalDate currentDate,
            LocalDate requiredDate) {
        requireNonNull(supplier);
        requireNonNull(requiredMaterial);
        requireNonNull(currentDate);
        requireNonNull(requiredDate);

        if (supplier.getStatus() != SupplierStatus.ACTIVE) {
            return SupplierConstraintEvaluation.disqualified(DisqualificationReason.SUPPLIER_INACTIVE);
        }
        if (supplierMaterial == null
                || !isSameSupplier(supplier, supplierMaterial.getSupplier())
                || !isSameMaterial(requiredMaterial, supplierMaterial.getMaterial())) {
            return SupplierConstraintEvaluation.disqualified(DisqualificationReason.SUPPLIER_MATERIAL_NOT_FOUND);
        }
        if (supplierMaterial.getStatus() != SupplierMaterialStatus.ACTIVE) {
            return SupplierConstraintEvaluation.disqualified(DisqualificationReason.SUPPLIER_MATERIAL_INACTIVE);
        }
        if (currentDate.plusDays(supplierMaterial.getLeadTimeDays()).isAfter(requiredDate)) {
            return SupplierConstraintEvaluation.disqualified(DisqualificationReason.DELIVERY_DATE_NOT_FEASIBLE);
        }

        return SupplierConstraintEvaluation.qualifiedResult();
    }

    private static boolean isSameSupplier(Supplier expected, Supplier actual) {
        return actual != null && isSameEntity(expected.getId(), actual.getId(), expected == actual);
    }

    private static boolean isSameMaterial(Material expected, Material actual) {
        return actual != null && isSameEntity(expected.getId(), actual.getId(), expected == actual);
    }

    private static boolean isSameEntity(Long expectedId, Long actualId, boolean sameReference) {
        return expectedId != null && actualId != null ? expectedId.equals(actualId) : sameReference;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
