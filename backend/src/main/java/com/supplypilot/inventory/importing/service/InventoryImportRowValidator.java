package com.supplypilot.inventory.importing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.inventory.importing.domain.ValidatedInventoryImportRow;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;

import org.springframework.stereotype.Component;

@Component
public class InventoryImportRowValidator {

    private final MaterialRepository materialRepository;

    public InventoryImportRowValidator(MaterialRepository materialRepository) {
        this.materialRepository = materialRepository;
    }

    public List<ValidatedInventoryImportRow> validate(List<ParsedInventoryImportRow> rows) {
        Set<String> encounteredMaterialCodes = new HashSet<>();
        List<ValidatedInventoryImportRow> validatedRows = new ArrayList<>();
        for (ParsedInventoryImportRow row : rows) {
            validatedRows.add(validateRow(row, encounteredMaterialCodes));
        }
        return validatedRows;
    }

    private ValidatedInventoryImportRow validateRow(ParsedInventoryImportRow row, Set<String> encounteredMaterialCodes) {
        String materialCodeSnapshot = row.materialCode();
        String normalizedCode = normalizeCode(materialCodeSnapshot);
        String unit = normalizeOptional(row.unit());
        if (normalizedCode == null && normalizeOptional(row.quantity()) == null && unit == null) {
            return invalid(row, null, materialCodeSnapshot, null, unit, ErrorCode.MATERIAL_CODE_REQUIRED);
        }
        if (!row.structurallyValid()) {
            return invalid(row, null, materialCodeSnapshot, null, unit, ErrorCode.INVALID_FILE_FORMAT);
        }
        if (normalizedCode == null) {
            return invalid(row, null, materialCodeSnapshot, null, unit, ErrorCode.MATERIAL_CODE_REQUIRED);
        }
        if (!encounteredMaterialCodes.add(normalizedCode)) {
            return invalid(row, null, materialCodeSnapshot, null, unit, ErrorCode.DUPLICATE_MATERIAL);
        }

        Material material = materialRepository.findByCode(normalizedCode).orElse(null);
        if (material == null) {
            return invalid(row, null, materialCodeSnapshot, null, unit, ErrorCode.MATERIAL_NOT_FOUND);
        }

        BigDecimal quantity = parseQuantity(row.quantity());
        if (quantity == null) {
            ErrorCode code = row.quantity() == null || row.quantity().trim().isEmpty()
                    ? ErrorCode.QUANTITY_REQUIRED : ErrorCode.INVALID_QUANTITY;
            return invalid(row, material, materialCodeSnapshot, null, unit, code);
        }
        if (quantity.signum() < 0) {
            return invalid(row, material, materialCodeSnapshot, quantity, unit, ErrorCode.NEGATIVE_QUANTITY);
        }
        if (unit == null || !material.getBaseUnit().name().equals(unit)) {
            return invalid(row, material, materialCodeSnapshot, quantity, unit, ErrorCode.UNIT_MISMATCH);
        }
        return new ValidatedInventoryImportRow(row.rowNumber(), material, materialCodeSnapshot, quantity, unit, null, null);
    }

    private static BigDecimal parseQuantity(String rawQuantity) {
        if (rawQuantity == null || rawQuantity.trim().isEmpty()) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(rawQuantity.trim());
            BigDecimal normalized = parsed.setScale(4, RoundingMode.UNNECESSARY);
            return normalized.precision() <= 19 ? normalized : null;
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    private static String normalizeCode(String code) {
        String trimmed = normalizeOptional(code);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ValidatedInventoryImportRow invalid(
            ParsedInventoryImportRow row, Material material, String materialCode, BigDecimal quantity,
            String unit, ErrorCode errorCode
    ) {
        return new ValidatedInventoryImportRow(
                row.rowNumber(), material, materialCode, quantity, unit, errorCode, errorCode.getDefaultMessage()
        );
    }
}
