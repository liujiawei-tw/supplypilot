package com.supplypilot.inventory.importing.domain;

import java.math.BigDecimal;

import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.Material;

public record ValidatedInventoryImportRow(
        int rowNumber,
        Material material,
        String materialCodeSnapshot,
        BigDecimal quantity,
        String unit,
        ErrorCode errorCode,
        String errorMessage
) {
    public boolean valid() {
        return errorCode == null;
    }
}
