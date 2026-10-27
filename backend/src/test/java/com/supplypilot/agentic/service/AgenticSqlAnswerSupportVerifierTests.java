package com.supplypilot.agentic.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgenticSqlAnswerSupportVerifierTests {
    private final AgenticSqlAnswerSupportVerifier verifier = new AgenticSqlAnswerSupportVerifier(new ObjectMapper());

    @Test
    void rejectsANumericClaimThatDiffersFromTheExecutedResult() {
        var result = verifier.verify(envelope("供應商 A 的報價為 USD 152.50。", "query-1", 0, "unit_price"),
                List.of(cell("query-1", "suppliers", 0, "unit_price", "125.50")));

        assertThat(result.answer()).isEmpty();
        assertThat(result.unsupportedClaims()).isEqualTo(1);
        assertThat(result.fullySupported()).isFalse();
    }

    @Test
    void rejectsAClaimCitingAValueProducedOnlyBySqlConstants() {
        var result = verifier.verify(envelope("供應商甲的報價為 999999。", "constant-query", 0, "unit_price"),
                List.of(constantCell("constant-query", "suppliers", 0, "unit_price", "999999")));

        assertThat(result.answer()).isEmpty();
        assertThat(result.unsupportedClaims()).isEqualTo(1);
        assertThat(result.fullySupported()).isFalse();
    }

    @Test
    void rejectsARowFactThatCitesARealPriceButOmitsItsSubject() {
        String claim = """
                {"claims":[{"text":"供應商甲的報價為 125.50。",
                "fact":{"kind":"FIELD_VALUE","attribute":"unit_price",
                "value":{"toolCallId":"q","rowIndex":0,"column":"unit_price"}},
                "evidence":[{"toolCallId":"q","rowIndex":0,"column":"unit_price"}]}]}
                """;
        var result = verifier.verify(claim,
                List.of(cell("q", "supplier_quotes", 0, "unit_price", "125.50")));

        assertThat(result.answer()).isEmpty();
        assertThat(result.unsupportedClaims()).isEqualTo(1);
    }

    @Test
    void rejectsInventedPurchaseOrdersAndResultRowReferences() {
        var cells = List.of(cell("query-1", "purchase_orders", 0, "po_number", "PO-100"),
                cell("query-1", "purchase_orders", 0, "total_amount", "125.50"));

        var inventedOrder = verifier.verify(envelope("PO-999 的金額為 125.50。", "query-1", 0,
                "po_number", "total_amount"), cells);
        var inventedRow = verifier.verify(envelope("PO-100 的金額為 125.50。", "query-1", 9,
                "po_number", "total_amount"), cells);

        assertThat(inventedOrder.answer()).isEmpty();
        assertThat(inventedRow.answer()).isEmpty();
    }

    @Test
    void acceptsAClaimSupportedByAnAggregateResultCellWithoutPrimaryKeys() {
        var result = verifier.verify(envelope("共找到 2 筆採購資料。", "aggregate-1", 0, "total_count"),
                List.of(new AgenticSqlEvidenceCell("aggregate-1", "MYSQL", Set.of("purchase_orders"), 0,
                        "total_count", "2", Set.of("@aggregate:COUNT:@rows:purchase_orders"), true)));

        assertThat(result.answer()).contains("2 筆");
        assertThat(result.fullySupported()).isTrue();
    }

    @Test
    void verifiesMinimumComparisonAgainstTheReferencedCandidates() {
        String answer = "供應商 A 報價最低，為 125.50。";
        String envelope = """
                {"claims":[{"text":"供應商 A 報價最低，為 125.50。",
                "fact":{"kind":"COMPARISON","attribute":"unit_price",
                "subject":{"toolCallId":"q","rowIndex":0,"column":"supplier_name"},
                "value":{"toolCallId":"q","rowIndex":0,"column":"unit_price"}},
                "evidence":[{"toolCallId":"q","rowIndex":0,"column":"supplier_name"},
                            {"toolCallId":"q","rowIndex":0,"column":"unit_price"},
                            {"toolCallId":"q","rowIndex":1,"column":"unit_price"}],
                "comparison":{"operator":"MIN","target":{"toolCallId":"q","rowIndex":0,"column":"unit_price"},
                "candidates":[{"toolCallId":"q","rowIndex":0,"column":"unit_price"},
                               {"toolCallId":"q","rowIndex":1,"column":"unit_price"}]}}]}
                """;
        var result = verifier.verify(envelope, List.of(
                cell("q", "supplier_quotes", 0, "supplier_name", "Supplier A"),
                cell("q", "supplier_quotes", 0, "unit_price", "125.50"),
                cell("q", "supplier_quotes", 1, "unit_price", "152.50")));

        assertThat(result.answer()).as("support result: %s", result).isEqualTo(answer);
        assertThat(result.fullySupported()).isTrue();
    }

    @Test
    void detectsContradictoryValuesForTheSamePurchaseOrderAcrossTables() {
        var cells = List.of(
                cell("q1", "purchase_orders", 0, "po_number", "PO-100"),
                cell("q1", "purchase_orders", 0, "total_amount", "125.50"),
                cell("q2", "purchase_order_summary", 0, "po_number", "PO-100"),
                cell("q2", "purchase_order_summary", 0, "total_amount", "152.50"));
        var result = verifier.verify(envelope("PO-100 的金額為 125.50。", "q1", 0,
                "po_number", "total_amount"), cells);

        assertThat(result.conflictingSources()).isTrue();
        assertThat(result.fullySupported()).isFalse();
    }

    @Test
    void rejectsUnsupportedQualitativeRiskAndRecommendationClaimsWithIrrelevantPriceEvidence() {
        var result = verifier.verify(envelope("Supplier A 交期不穩定，應暫停合作。", "query-1", 0,
                        "supplier_name", "unit_price"),
                List.of(cell("query-1", "supplier_quotes", 0, "supplier_name", "Supplier A"),
                        cell("query-1", "supplier_quotes", 0, "unit_price", "125.50")));

        assertThat(result.answer()).isEmpty();
        assertThat(result.unsupportedClaims()).isEqualTo(1);
        assertThat(result.fullySupported()).isFalse();
    }

    @Test
    void rejectsBankruptcyClaimsCitingOnlySupplierNameAndPriceInEnglishAndChinese() {
        var cells = List.of(cell("query-1", "supplier_quotes", 0, "supplier_name", "Supplier A"),
                cell("query-1", "supplier_quotes", 0, "unit_price", "125.50"));

        var english = verifier.verify(envelope("Supplier A is bankrupt.", "query-1", 0,
                "supplier_name", "unit_price"), cells);
        var chinese = verifier.verify(envelope("供應商甲已破產。", "query-1", 0,
                "supplier_name", "unit_price"), cells);

        assertThat(english.answer()).isEmpty();
        assertThat(chinese.answer()).isEmpty();
        assertThat(english.unsupportedClaims()).isEqualTo(1);
        assertThat(chinese.unsupportedClaims()).isEqualTo(1);
    }

    @Test
    void rejectsDeliveryReliabilityClaimWhenTheOnlyBusinessEvidenceIsAQuote() {
        var result = verifier.verify(envelope("Supplier A 的交期可靠。", "query-1", 0,
                        "supplier_name", "unit_price"),
                List.of(cell("query-1", "supplier_quotes", 0, "supplier_name", "Supplier A"),
                        cell("query-1", "supplier_quotes", 0, "unit_price", "125.50")));

        assertThat(result.answer()).isEmpty();
        assertThat(result.unsupportedClaims()).isEqualTo(1);
    }

    @Test
    void willNotAttributeAnotherSuppliersPriceToTheNamedSupplier() {
        String answer = """
                {"claims":[{"text":"Supplier A 的報價為 225.50。",
                "fact":{"kind":"FIELD_VALUE","attribute":"unit_price",
                "subject":{"toolCallId":"q","rowIndex":0,"column":"supplier_name"},
                "value":{"toolCallId":"q","rowIndex":1,"column":"unit_price"}},
                "evidence":[{"toolCallId":"q","rowIndex":0,"column":"supplier_name"},
                            {"toolCallId":"q","rowIndex":1,"column":"unit_price"}]}]}
                """;
        var result = verifier.verify(answer, List.of(
                cell("q", "supplier_quotes", 0, "supplier_name", "Supplier A"),
                cell("q", "supplier_quotes", 0, "unit_price", "125.50"),
                cell("q", "supplier_quotes", 1, "supplier_name", "Supplier B"),
                cell("q", "supplier_quotes", 1, "unit_price", "225.50")));

        assertThat(result.answer()).isEmpty();
        assertThat(result.unsupportedClaims()).isEqualTo(1);
    }

    @Test
    void acceptsQualitativeConclusionExplicitlyPresentInTheCitedSourceValue() {
        var result = verifier.verify(envelope("Supplier A is unreliable.", "query-1", 0,
                        "supplier_name", "risk_status"),
                List.of(cell("query-1", "supplier_quotes", 0, "supplier_name", "Supplier A"),
                        cell("query-1", "supplier_quotes", 0, "risk_status", "unreliable")));

        assertThat(result.answer()).as("support result: %s", result).contains("unreliable");
        assertThat(result.fullySupported()).isTrue();
    }

    private static String envelope(String claim, String toolCallId, int rowIndex, String... columns) {
        String references = java.util.Arrays.stream(columns).map(column ->
                "{\"toolCallId\":\"" + toolCallId + "\",\"rowIndex\":" + rowIndex
                        + ",\"column\":\"" + column + "\"}").collect(java.util.stream.Collectors.joining(","));
        String valueColumn = columns[columns.length - 1];
        String subject = columns.length > 1
                ? ",\"subject\":{\"toolCallId\":\"" + toolCallId + "\",\"rowIndex\":" + rowIndex
                        + ",\"column\":\"" + columns[0] + "\"}"
                : "";
        String kind = columns.length == 1 && valueColumn.toLowerCase().contains("count")
                ? "AGGREGATE" : "FIELD_VALUE";
        String attribute = "AGGREGATE".equals(kind) ? "COUNT(*)" : valueColumn;
        String fact = "\"fact\":{\"kind\":\"" + kind + "\",\"attribute\":\"" + attribute + "\""
                + subject + ",\"value\":{\"toolCallId\":\"" + toolCallId + "\",\"rowIndex\":" + rowIndex
                + ",\"column\":\"" + valueColumn + "\"}}";
        return "{\"claims\":[{\"text\":\"" + claim + "\"," + fact
                + ",\"evidence\":[" + references + "]}]}";
    }

    private static AgenticSqlEvidenceCell cell(String call, String table, int row, String column, String value) {
        return new AgenticSqlEvidenceCell(call, "MYSQL", Set.of(table), row, column, value);
    }

    private static AgenticSqlEvidenceCell constantCell(String call, String table, int row, String column, String value) {
        return new AgenticSqlEvidenceCell(call, "MYSQL", Set.of(table), row, column, value, Set.of(), false);
    }
}
