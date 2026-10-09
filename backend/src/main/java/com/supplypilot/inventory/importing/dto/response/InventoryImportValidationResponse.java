package com.supplypilot.inventory.importing.dto.response;

import java.util.List;

import com.supplypilot.inventory.importing.domain.InventoryImportStatus;

public record InventoryImportValidationResponse(
        Long batchId,
        InventoryImportStatus status,
        int totalRows,
        int validRows,
        int errorRows,
        List<InventoryImportErrorResponse> errors
) {
}
