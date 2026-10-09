package com.supplypilot.inventory.importing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.material.repository.MaterialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryImportRowValidatorTests {

    @Mock private MaterialRepository materialRepository;
    private InventoryImportRowValidator validator;
    private Material material;

    @BeforeEach
    void setUp() {
        validator = new InventoryImportRowValidator(materialRepository);
        material = Material.create("MAT-001", "Material", BaseUnit.KG, BigDecimal.ZERO, Instant.EPOCH);
        when(materialRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(materialRepository.findByCode("MAT-001")).thenReturn(Optional.of(material));
    }

    @Test
    void reportsRequiredMissingAndNumericValidationErrorsWithoutThrowing() {
        List<ErrorCode> errors = List.of(
                error(row(2, "", "", "")), error(row(3, "UNKNOWN", "1", "KG")),
                error(row(4, "MAT-001", "", "KG")), error(row(5, "MAT-001", "not-a-number", "KG")),
                error(row(6, "MAT-001", "-1", "KG")), error(row(7, "MAT-001", "1.00001", "KG"))
        );

        assertThat(errors).containsExactly(
                ErrorCode.MATERIAL_CODE_REQUIRED, ErrorCode.MATERIAL_NOT_FOUND, ErrorCode.QUANTITY_REQUIRED,
                ErrorCode.INVALID_QUANTITY, ErrorCode.NEGATIVE_QUANTITY, ErrorCode.INVALID_QUANTITY
        );
    }

    @Test
    void acceptsZeroAndFlagsUnitMismatchDuplicateAndMalformedRows() {
        when(materialRepository.findByCode("MAT-002")).thenReturn(Optional.of(
                Material.create("MAT-002", "Material 2", BaseUnit.KG, BigDecimal.ZERO, Instant.EPOCH)
        ));
        List<ParsedInventoryImportRow> rows = List.of(
                row(2, "MAT-001", "0", "KG"), row(3, "MAT-001", "1", "KG"),
                row(4, "MAT-002", "1", "PCS"), new ParsedInventoryImportRow(5, "MAT-003", null, null, false)
        );

        var results = validator.validate(rows);

        assertThat(results.get(0).valid()).isTrue();
        assertThat(results.get(0).quantity()).isEqualByComparingTo("0.0000");
        assertThat(results).extracting(result -> result.errorCode()).containsExactly(
                null, ErrorCode.DUPLICATE_MATERIAL, ErrorCode.UNIT_MISMATCH, ErrorCode.INVALID_FILE_FORMAT
        );
    }

    @Test
    void validatesUnitMismatchAndAllValidRowsWithNormalizedMaterialCodes() {
        when(materialRepository.findByCode("MAT-002")).thenReturn(Optional.of(
                Material.create("MAT-002", "Material 2", BaseUnit.KG, BigDecimal.ZERO, Instant.EPOCH)
        ));
        var results = validator.validate(List.of(
                row(2, " mat-001 ", "1", "PCS"), row(3, "mat-002", "2.5", "KG")
        ));

        assertThat(results.get(0).errorCode()).isEqualTo(ErrorCode.UNIT_MISMATCH);
        assertThat(results.get(1).valid()).isTrue();
    }

    private ParsedInventoryImportRow row(int number, String materialCode, String quantity, String unit) {
        return new ParsedInventoryImportRow(number, materialCode, quantity, unit, true);
    }

    private ErrorCode error(ParsedInventoryImportRow row) {
        return validator.validate(List.of(row)).getFirst().errorCode();
    }
}
