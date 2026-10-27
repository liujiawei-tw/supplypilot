package com.supplypilot.agentic.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgenticSqlAnswerSupportVerifierTests {
    private final AgenticSqlAnswerSupportVerifier verifier = new AgenticSqlAnswerSupportVerifier(new ObjectMapper());

    @Test
    void classifiesInvalidJsonWithoutReturningTheModelOutput() {
        String output = "```json\n{\"claims\":[]}\n```";

        var result = verifier.verify(output, List.of());

        assertThat(result.failureCategories()).containsExactly(
                "INVALID_MODEL_OUTPUT_JSON", "MODEL_OUTPUT_MARKDOWN_FENCE");
        assertThat(result.answer()).isEmpty();
        assertThat(result.toString()).doesNotContain(output);
    }

    @Test
    void distinguishesInvalidClaimsShapeFromJsonParseFailure() {
        var result = verifier.verify("{\"claims\":{\"text\":\"供應商甲的報價為 125.50。\"}}", List.of());

        assertThat(result.failureCategories()).containsExactly("INVALID_MODEL_CLAIMS_STRUCTURE");
        assertThat(result.malformed()).isTrue();
    }

    @Test
    void classifiesTruncatedJsonWithoutRetainingTheOutput() {
        String output = "{\"claims\":[";

        var result = verifier.verify(output, List.of());

        assertThat(result.failureCategories()).containsExactly(
                "INVALID_MODEL_OUTPUT_JSON", "MODEL_OUTPUT_JSON_TRUNCATED");
        assertThat(result.toString()).doesNotContain(output);
    }

    @Test
    void classifiesNonJsonPrefixWithoutRetainingTheOutput() {
        String output = "The query result is 100 TWD";

        var result = verifier.verify(output, List.of());

        assertThat(result.failureCategories()).containsExactly(
                "INVALID_MODEL_OUTPUT_JSON", "MODEL_OUTPUT_NON_OBJECT_PREFIX");
        assertThat(result.toString()).doesNotContain(output);
    }

    @Test
    void classifiesEvidenceReferencesThatDoNotExistInThisRequest() {
        String output = envelope("Supplier A 的報價為 USD 125.50。", "invented-call", 0,
                "supplier_name", "unit_price");

        var result = verifier.verify(output, List.of(
                cell("actual-call", "suppliers", 0, "supplier_name", "Supplier A"),
                cell("actual-call", "suppliers", 0, "unit_price", "125.50")));

        assertThat(result.failureCategories()).containsExactly("INVALID_MODEL_EVIDENCE_REFERENCE");
        assertThat(result.unsupportedClaims()).isEqualTo(1);
    }

    @Test
    void classifiesUnexpectedVerifierExceptionsWithoutExposingTheirMessages() {
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public JsonNode readTree(String content) throws JsonProcessingException {
                throw new IllegalStateException("sensitive diagnostic detail");
            }
        };

        var result = new AgenticSqlAnswerSupportVerifier(failingMapper)
                .verify("model output", List.of());

        assertThat(result.failureCategories()).containsExactly("MODEL_CLAIMS_VERIFICATION_EXCEPTION");
        assertThat(result.toString()).doesNotContain("model output", "sensitive diagnostic detail");
    }

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
    void acceptsDirectCountSumAndAverageAggregates() {
        var count = verifier.verify(aggregateClaim("共找到 2 筆採購資料。", "AGGREGATE", "COUNT(*)",
                        "direct-count", "total_count", "2"),
                List.of(aggregateCell("direct-count", "total_count", "2",
                        "@aggregate:COUNT:@rows:supplier_materials")));
        var sum = verifier.verify(aggregateClaim("報價總和為 250.50。", "AGGREGATE", "SUM(unit_price)",
                        "direct-sum", "total_price", "250.50"),
                List.of(aggregateCell("direct-sum", "total_price", "250.50",
                        "supplier_materials.unit_price", "@aggregate:SUM:supplier_materials.unit_price")));
        var average = verifier.verify(aggregateClaim("平均報價為 125.25。", "AGGREGATE", "AVG(unit_price)",
                        "direct-average", "average_price", "125.25"),
                List.of(aggregateCell("direct-average", "average_price", "125.25",
                        "supplier_materials.unit_price", "@aggregate:AVG:supplier_materials.unit_price")));

        assertThat(count.fullySupported()).isTrue();
        assertThat(sum.fullySupported()).isTrue();
        assertThat(average.fullySupported()).isTrue();
    }

    @Test
    void derivedAggregateMustBeLabeledAsCalculationAndCannotMasqueradeAsDirectSum() {
        var cell = aggregateCell("derived-sum", "adjusted_total", "2036.50",
                "supplier_materials.unit_price", "@derived-aggregate:SUM(unit_price + 999)");
        var unsupportedDirectAggregate = verifier.verify(aggregateClaim(
                "報價總和為 2036.50。", "AGGREGATE", "SUM(unit_price)",
                "derived-sum", "adjusted_total", "2036.50"), List.of(cell));
        var explicitCalculation = verifier.verify(aggregateClaim(
                "計算後的報價總和為 2036.50。", "DERIVED", "SUM(unit_price + 999)",
                "derived-sum", "adjusted_total", "2036.50"), List.of(cell));

        assertThat(unsupportedDirectAggregate.answer()).isEmpty();
        assertThat(unsupportedDirectAggregate.fullySupported()).isFalse();
        assertThat(explicitCalculation.answer()).contains("計算後");
        assertThat(explicitCalculation.fullySupported()).isTrue();
    }

    @Test
    void groupedDerivedAggregateRequiresItsSameRowSubject() {
        var subject = cell("grouped-sum", "supplier_materials", 0, "supplier_id", "7");
        var total = new AgenticSqlEvidenceCell("grouped-sum", "MYSQL", Set.of("supplier_materials"), 0,
                "adjusted_total", "2035.50", Set.of("supplier_materials.unit_price",
                "@derived-aggregate:SUM(unit_price + 999)", "@grouped-aggregate"), true);
        var missingSubject = verifier.verify(aggregateClaim("供應商代碼 7 計算後總額為 2035.50。",
                "DERIVED", "SUM(unit_price + 999)", "grouped-sum", "adjusted_total", "2035.50"),
                List.of(subject, total));
        var supported = verifier.verify("""
                {"claims":[{"text":"供應商代碼 7 計算後總額為 2035.50。",
                "fact":{"kind":"DERIVED","attribute":"SUM(unit_price + 999)",
                "subject":{"toolCallId":"grouped-sum","rowIndex":0,"column":"supplier_id"},
                "value":{"toolCallId":"grouped-sum","rowIndex":0,"column":"adjusted_total"}},
                "evidence":[{"toolCallId":"grouped-sum","rowIndex":0,"column":"supplier_id"},
                            {"toolCallId":"grouped-sum","rowIndex":0,"column":"adjusted_total"}]}]}
                """, List.of(subject, total));

        assertThat(missingSubject.answer()).isEmpty();
        assertThat(supported.answer()).contains("計算後");
        assertThat(supported.fullySupported()).isTrue();
    }

    @Test
    void acceptsAnExplicitlyLabeledNumericCalculationWithoutTreatingItAsARawField() {
        var result = verifier.verify("""
                {"claims":[{"text":"Supplier A 的 line total 經計算為 251.00。",
                "fact":{"kind":"DERIVED","attribute":"unit_price * quantity",
                "subject":{"toolCallId":"q","rowIndex":0,"column":"supplier_name"},
                "value":{"toolCallId":"q","rowIndex":0,"column":"line_total"}},
                "evidence":[{"toolCallId":"q","rowIndex":0,"column":"supplier_name"},
                            {"toolCallId":"q","rowIndex":0,"column":"line_total"}]}]}
                """, List.of(
                cell("q", "supplier_quotes", 0, "supplier_name", "Supplier A"),
                new AgenticSqlEvidenceCell("q", "MYSQL", Set.of("supplier_quotes"), 0,
                        "line_total", "251.00", Set.of("supplier_quotes.unit_price", "supplier_quotes.quantity",
                        "@derived-numeric:unit_price * quantity"), true)));

        assertThat(result.answer()).contains("251.00");
        assertThat(result.fullySupported()).isTrue();
    }

    @Test
    void doesNotAcceptNumericDerivedValuesAsRawFieldClaims() {
        var result = verifier.verify(envelope("Supplier A 的 unit_price 為 251.00。", "q", 0,
                        "supplier_name", "line_total"), List.of(
                cell("q", "supplier_quotes", 0, "supplier_name", "Supplier A"),
                new AgenticSqlEvidenceCell("q", "MYSQL", Set.of("supplier_quotes"), 0,
                        "line_total", "251.00", Set.of("supplier_quotes.unit_price", "supplier_quotes.quantity",
                        "@derived-numeric:unit_price * quantity"), true)));

        assertThat(result.answer()).isEmpty();
        assertThat(result.fullySupported()).isFalse();
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

    private static String aggregateClaim(String claim, String kind, String attribute,
                                         String toolCallId, String column, String value) {
        return """
                {"claims":[{"text":"%s","fact":{"kind":"%s","attribute":"%s",
                "value":{"toolCallId":"%s","rowIndex":0,"column":"%s"}},
                "evidence":[{"toolCallId":"%s","rowIndex":0,"column":"%s"}]}]}
                """.formatted(claim, kind, attribute, toolCallId, column, toolCallId, column);
    }

    private static AgenticSqlEvidenceCell aggregateCell(String call, String column, String value,
                                                        String... sources) {
        return new AgenticSqlEvidenceCell(call, "MYSQL", Set.of("supplier_materials"), 0, column, value,
                Set.of(sources), true);
    }

    private static AgenticSqlEvidenceCell cell(String call, String table, int row, String column, String value) {
        return new AgenticSqlEvidenceCell(call, "MYSQL", Set.of(table), row, column, value);
    }

    private static AgenticSqlEvidenceCell constantCell(String call, String table, int row, String column, String value) {
        return new AgenticSqlEvidenceCell(call, "MYSQL", Set.of(table), row, column, value, Set.of(), false);
    }
}
