package com.supplypilot.inventory.importing.dto.response;

public record InventoryImportErrorResponse(int row, String materialCode, String code, String message) {
}
