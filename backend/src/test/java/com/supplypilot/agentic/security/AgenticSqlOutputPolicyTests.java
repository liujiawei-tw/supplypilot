package com.supplypilot.agentic.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgenticSqlOutputPolicyTests {
    private final AgenticSqlOutputPolicy policy = new AgenticSqlOutputPolicy(new ObjectMapper().findAndRegisterModules());

    @Test
    void permitsCommercialFactsWhileMaskingPersonalAndBankIdentifiers() {
        String result = policy.maskColumnValue("description",
                "報價 USD 12,340.50，聯絡 email amy@example.com，手機 0912-345-678，身分證 A123456789，銀行帳號 123456789012");

        assertThat(result).contains("USD 12,340.50");
        assertThat(result).doesNotContain("amy@example.com", "0912-345-678", "A123456789", "123456789012");
        assertThat(result).contains("[EMAIL_REDACTED]", "[PHONE_REDACTED]", "[ID_REDACTED]",
                "[BANK_ACCOUNT_REDACTED]");
    }

    @Test
    void masksQuestionIdentifiersButKeepsTheBusinessQuestionAndPriceTerms() {
        String result = policy.sanitizeQuestion("比較 USD 500 報價，聯絡信箱 amy@example.com，電話 0912-345-678");

        assertThat(result).contains("USD 500", "報價");
        assertThat(result).doesNotContain("amy@example.com", "0912-345-678");
    }

    @Test
    void removesCredentialColumnsAndMasksSensitiveIdentifierColumns() {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("supplier_name", "供應商甲");
        row.put("unit_price", 125.50);
        row.put("api_key", "secret-value");
        row.put("bank_account_number", "123456789012");

        AgenticSqlOutputPolicy.SanitizedRows result = policy.sanitizeRows(List.of(row), 10, 4096);

        assertThat(result.rows()).containsExactly(Map.of(
                "supplier_name", "供應商甲",
                "unit_price", "125.5",
                "bank_account_number", "[REDACTED_IDENTIFIER]"));
        assertThat(policy.isNeverSendColumn("PASSWORD_HASH")).isTrue();
        assertThat(policy.isNeverSendColumn("refresh_token")).isTrue();
        assertThat(result.rows().toString()).doesNotContain("secret-value");
    }

    @Test
    void boundsColumnsAndSerializedResultCharacters() {
        LinkedHashMap<String, Object> first = new LinkedHashMap<>();
        first.put("id", 1);
        first.put("name", "一個較長的業務資料列");
        LinkedHashMap<String, Object> second = new LinkedHashMap<>();
        second.put("id", 2);
        second.put("name", "第二筆資料");

        AgenticSqlOutputPolicy.SanitizedRows byColumns = policy.sanitizeRows(List.of(first), 1, 4096);
        AgenticSqlOutputPolicy.SanitizedRows byCharacters = policy.sanitizeRows(List.of(first, second), 10, 16);

        assertThat(byColumns.rows().getFirst()).containsOnlyKeys("id");
        assertThat(byColumns.truncated()).isTrue();
        assertThat(byCharacters.rows()).isEmpty();
        assertThat(byCharacters.truncated()).isTrue();
    }

    @Test
    void masksCredentialAssignmentsEmbeddedInBusinessText() {
        String result = policy.maskColumnValue("note", "使用者記錄 password=do-not-send; 報價 TWD 300");

        assertThat(result).contains("報價 TWD 300", "[SYSTEM_SECRET_REDACTED]");
        assertThat(result).doesNotContain("do-not-send");
    }

    @Test
    void masksNumericBankAndCardIdentifiersBeforeSerializingTheModelPayload() {
        Map<String, Object> row = Map.of(
                "bank_acct_no", 123456789012L,
                "card_number", new java.math.BigInteger("4111111111111111"),
                "unit_price", new java.math.BigDecimal("1250.75"));

        AgenticSqlOutputPolicy.SanitizedRows result = policy.sanitizeRows(List.of(row), 10, 4096);

        assertThat(result.rows()).containsExactly(Map.of(
                "bank_acct_no", "[REDACTED_IDENTIFIER]",
                "card_number", "[REDACTED_IDENTIFIER]",
                "unit_price", "1250.75"));
        assertThat(result.rows().toString()).doesNotContain("123456789012", "4111111111111111");
    }

    @Test
    void masksVeryLongUnclassifiedNumericIdentifiers() {
        String value = "12345678901234567890";

        assertThat(policy.maskColumnValue("memo", value)).isEqualTo("[IDENTIFIER_REDACTED]");
        assertThat(policy.sanitizeQuestion(value)).doesNotContain(value);
    }

    @Test
    void masksCommonEmployeeAndApprovalNameColumnsWithoutMaskingSupplierNames() {
        for (String column : List.of("employee_name", "approver_name", "buyer_name", "requester_name",
                "recipient_name", "operator_name")) {
            assertThat(policy.maskColumnValue(column, "Alice Chen"))
                    .as("column %s", column).isEqualTo("[REDACTED_IDENTIFIER]");
        }
        assertThat(policy.maskColumnValue("supplier_name", "Example Supplier Ltd."))
                .isEqualTo("Example Supplier Ltd.");
    }
}
