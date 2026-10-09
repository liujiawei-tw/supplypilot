package com.supplypilot.inventory.importing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class InventoryCsvParserTests {

    private final InventoryCsvParser parser = new InventoryCsvParser();

    @Test
    void parsesQuotedFieldsEscapedQuotesAndOriginalRecordNumbers() {
        MockMultipartFile file = csv("inventory.csv", """
                material_code,quantity,unit
                "MAT,001",2,KG
                "MAT""002",3,KG
                ""
                """);

        var rows = parser.parse(file);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).extracting(ParsedInventoryImportRow::rowNumber,
                ParsedInventoryImportRow::materialCode).containsExactly(2, "MAT,001");
        assertThat(rows.get(1)).extracting(ParsedInventoryImportRow::rowNumber,
                ParsedInventoryImportRow::materialCode).containsExactly(3, "MAT\"002");
        assertThat(rows.get(2).rowNumber()).isEqualTo(4);
    }

    @Test
    void acceptsUtf8BomAndRejectsMissingOrWrongHeaders() {
        MockMultipartFile bomFile = csv("inventory.csv", "\uFEFFmaterial_code,quantity,unit\nMAT-001,1,KG\n");
        assertThat(parser.parse(bomFile)).singleElement().satisfies(row -> assertThat(row.rowNumber()).isEqualTo(2));

        MockMultipartFile invalidHeader = csv("inventory.csv", "material,quantity,unit\nMAT-001,1,KG\n");
        assertThatThrownBy(() -> parser.parse(invalidHeader)).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_FILE_FORMAT);
    }

    @Test
    void rejectsEmptyAndNonCsvUploads() {
        assertThatThrownBy(() -> parser.parse(csv("inventory.csv", ""))).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.FILE_EMPTY);
        MockMultipartFile json = new MockMultipartFile("file", "inventory.json", "application/json", "{}".getBytes());
        assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_FILE_FORMAT);
    }

    private MockMultipartFile csv(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/csv", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
