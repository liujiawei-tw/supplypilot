package com.supplypilot.inventory.importing.service;

public record ParsedInventoryImportRow(int rowNumber, String materialCode, String quantity, String unit, boolean structurallyValid) {
}
