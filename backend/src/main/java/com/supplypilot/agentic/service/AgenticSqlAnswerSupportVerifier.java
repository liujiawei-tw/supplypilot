package com.supplypilot.agentic.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Validates structured model claims against bounded, sanitized result cells from this request. */
@Component
public final class AgenticSqlAnswerSupportVerifier {
    private static final int MAX_CLAIMS = 32;
    private static final int MAX_REFERENCES_PER_CLAIM = 32;
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}])[-+]?\\d[\\d,]*(?:\\.\\d+)?%?(?![\\p{L}\\p{N}])");
    private static final Pattern BUSINESS_IDENTIFIER = Pattern.compile(
            "(?i)\\b(?:P\\.?O\\.?|PO|DOC(?:UMENT)?|SUPPLIER|MATERIAL|SKU|ITEM)[-_#:\\s]*"
                    + "[A-Z0-9][A-Z0-9_-]*\\b");
    private static final Pattern PO_KEY_COLUMN = Pattern.compile(
            "(?i)^(?:po(?:_?number|_?no|_?id)?|purchase_?order(?:_?number|_?no|_?id)?)$");
    private static final Pattern COMPARISON_FIELD = Pattern.compile(
            "(?i).*(?:amount|price|cost|total|status|quantity|qty|currency).*");
    private static final List<String> UNSUPPORTED_CONCLUSION_PHRASES = List.of(
            "不穩定", "不可靠", "風險", "建議", "應該", "應暫停", "暫停合作", "停止合作", "汰換",
            "異常", "不適合", "適合", "不合理", "合理", "可靠", "穩定", "良好", "優良", "優秀",
            "不佳", "不足", "充足", "嚴重", "過高", "過低", "逾期", "遲交", "違約", "合規", "違規",
            "品質好", "服務差", "unreliable", "unstable", "risk", "recommend", "should", "suspend",
            "stop working with", "unsuitable", "suitable", "abnormal", "good", "bad", "poor", "excellent",
            "insufficient", "sufficient", "severe", "overdue", "late", "breach", "compliant", "noncompliant",
            "acceptable", "unacceptable", "caused", "causes", "leads to", "proves", "demonstrates");
    private static final Set<String> COMPARISON_CONCLUSION_PHRASES = Set.of(
            "最低", "最高", "最便宜", "最昂貴", "較低", "較高", "大於", "小於", "不高於", "不低於",
            "lowest", "highest", "cheapest", "most expensive", "greater than", "less than");
    private static final Set<String> MASKED_VALUES = Set.of(
            "[REDACTED_IDENTIFIER]", "[BANK_ACCOUNT_REDACTED]", "[BANK_IDENTIFIER_REDACTED]",
            "[ID_REDACTED]", "[EMAIL_REDACTED]", "[PHONE_REDACTED]", "[PERSONAL_DATA_REDACTED]",
            "[SYSTEM_SECRET_REDACTED]", "[IDENTIFIER_REDACTED]", "[SENSITIVE_DATA_REDACTED]");

    private final ObjectMapper objectMapper;

    public AgenticSqlAnswerSupportVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgenticSqlSupportResult verify(String output, List<AgenticSqlEvidenceCell> availableCells) {
        boolean conflictingSources = hasConflictingSources(availableCells);
        try {
            JsonNode root = objectMapper.readTree(output);
            JsonNode claims = root == null ? null : root.get("claims");
            if (root == null || !root.isObject() || claims == null || !claims.isArray()
                    || claims.isEmpty() || claims.size() > MAX_CLAIMS) {
                return new AgenticSqlSupportResult("", 1, true, conflictingSources);
            }
            Map<CellKey, AgenticSqlEvidenceCell> cells = new HashMap<>();
            for (AgenticSqlEvidenceCell cell : availableCells) {
                cells.put(new CellKey(cell.toolCallId(), cell.rowIndex(), normalizeColumn(cell.column())), cell);
            }

            List<String> supportedClaims = new ArrayList<>();
            int unsupported = 0;
            for (JsonNode claim : claims) {
                String text = textValue(claim.get("text"));
                JsonNode evidence = claim.get("evidence");
                if (text == null || text.isBlank() || text.length() > 2000 || evidence == null
                        || !evidence.isArray() || evidence.isEmpty()
                        || evidence.size() > MAX_REFERENCES_PER_CLAIM) {
                    unsupported++;
                    continue;
                }
                List<AgenticSqlEvidenceCell> cited = resolveReferences(evidence, cells);
                boolean comparisonValid = cited != null
                        && validComparison(text, claim.get("comparison"), cited, cells);
                if (cited == null || !containsClaimFacts(text, cited) || !comparisonValid
                        || hasUnsupportedConclusion(text, cited, comparisonValid)) {
                    unsupported++;
                    continue;
                }
                supportedClaims.add(text.strip());
            }
            return new AgenticSqlSupportResult(String.join("\n", supportedClaims), unsupported, false,
                    conflictingSources);
        } catch (Exception malformed) {
            return new AgenticSqlSupportResult("", 1, true, conflictingSources);
        }
    }

    private boolean validComparison(String claimText, JsonNode comparison,
                                    List<AgenticSqlEvidenceCell> cited,
                                    Map<CellKey, AgenticSqlEvidenceCell> cells) {
        boolean comparativeLanguage = containsComparisonLanguage(claimText);
        if (comparison == null || comparison.isNull()) return !comparativeLanguage;
        String operator = textValue(comparison.get("operator"));
        if (!"MIN".equals(operator) && !"MAX".equals(operator)) return false;
        String expectedOperator = expectedComparisonOperator(claimText);
        if (expectedOperator != null && !expectedOperator.equals(operator)) return false;
        AgenticSqlEvidenceCell target = resolveReference(comparison.get("target"), cells);
        JsonNode candidateRefs = comparison.get("candidates");
        if (target == null || candidateRefs == null || !candidateRefs.isArray() || candidateRefs.size() < 2
                || candidateRefs.size() > MAX_REFERENCES_PER_CLAIM || !cited.contains(target)) return false;
        BigDecimal targetValue = decimal(target.value());
        if (targetValue == null) return false;
        String targetColumn = normalizeColumn(target.column());
        BigDecimal extreme = targetValue;
        boolean targetSeen = false;
        for (JsonNode reference : candidateRefs) {
            AgenticSqlEvidenceCell cell = resolveReference(reference, cells);
            if (cell == null || !cited.contains(cell)
                    || !targetColumn.equals(normalizeColumn(cell.column()))) return false;
            BigDecimal value = decimal(cell.value());
            if (value == null) return false;
            if (cell.equals(target)) targetSeen = true;
            extreme = "MIN".equals(operator) ? extreme.min(value) : extreme.max(value);
        }
        return targetSeen && targetValue.compareTo(extreme) == 0;
    }

    private List<AgenticSqlEvidenceCell> resolveReferences(JsonNode references,
                                                           Map<CellKey, AgenticSqlEvidenceCell> cells) {
        LinkedHashMap<CellKey, AgenticSqlEvidenceCell> result = new LinkedHashMap<>();
        for (JsonNode reference : references) {
            AgenticSqlEvidenceCell cell = resolveReference(reference, cells);
            if (cell == null) return null;
            result.put(new CellKey(cell.toolCallId(), cell.rowIndex(), normalizeColumn(cell.column())), cell);
        }
        return List.copyOf(result.values());
    }

    private AgenticSqlEvidenceCell resolveReference(JsonNode reference,
                                                     Map<CellKey, AgenticSqlEvidenceCell> cells) {
        if (reference == null || !reference.isObject()) return null;
        String toolCallId = textValue(reference.get("toolCallId"));
        String column = textValue(reference.get("column"));
        JsonNode rowIndex = reference.get("rowIndex");
        if (toolCallId == null || column == null || rowIndex == null || !rowIndex.canConvertToInt()) return null;
        AgenticSqlEvidenceCell cell = cells.get(new CellKey(toolCallId, rowIndex.asInt(), normalizeColumn(column)));
        if (cell == null || cell.value() == null || MASKED_VALUES.contains(cell.value())) return null;
        return cell;
    }

    private boolean containsClaimFacts(String claim, List<AgenticSqlEvidenceCell> cited) {
        List<String> values = cited.stream().map(AgenticSqlEvidenceCell::value).filter(value -> value != null).toList();
        Set<String> availableNumbers = new HashSet<>();
        for (String value : values) {
            Matcher matcher = NUMBER.matcher(value);
            while (matcher.find()) {
                String normalized = normalizeNumber(matcher.group());
                if (normalized != null) availableNumbers.add(normalized);
            }
        }
        Matcher numbers = NUMBER.matcher(claim);
        while (numbers.find()) {
            String normalized = normalizeNumber(numbers.group());
            if (normalized == null || !availableNumbers.contains(normalized)) return false;
        }

        Set<String> identifiers = new LinkedHashSet<>();
        Matcher businessIdentifiers = BUSINESS_IDENTIFIER.matcher(claim);
        while (businessIdentifiers.find()) identifiers.add(normalizeText(businessIdentifiers.group()));
        for (String identifier : identifiers) {
            if (values.stream().map(this::normalizeText)
                    .noneMatch(value -> value.contains(identifier))) return false;
        }
        return true;
    }

    /**
     * SQL cells can verify values and checked comparisons, but cannot substantiate an unrelated
     * recommendation or causal judgment merely because the model attached a valid cell reference.
     * Explicit status/risk values remain usable when the source value states the same conclusion.
     */
    private boolean hasUnsupportedConclusion(String claim, List<AgenticSqlEvidenceCell> cited,
                                             boolean comparisonValid) {
        String normalizedClaim = normalizeText(claim);
        List<String> normalizedValues = cited.stream().map(AgenticSqlEvidenceCell::value)
                .filter(value -> value != null).map(this::normalizeText).toList();
        for (String phrase : UNSUPPORTED_CONCLUSION_PHRASES) {
            if (!normalizedClaim.contains(normalizeText(phrase))) continue;
            if (COMPARISON_CONCLUSION_PHRASES.contains(phrase) && comparisonValid) continue;
            String normalizedPhrase = normalizeText(phrase);
            if (normalizedValues.stream().noneMatch(value -> value.contains(normalizedPhrase))) return true;
        }
        return false;
    }

    private boolean hasConflictingSources(List<AgenticSqlEvidenceCell> evidence) {
        Map<String, List<AgenticSqlEvidenceCell>> rows = new LinkedHashMap<>();
        for (AgenticSqlEvidenceCell cell : evidence) {
            String rowKey = cell.toolCallId() + ":" + cell.rowIndex();
            rows.computeIfAbsent(rowKey, ignored -> new ArrayList<>()).add(cell);
        }
        Map<String, Map<String, Map<String, String>>> valuesByEntity = new HashMap<>();
        for (List<AgenticSqlEvidenceCell> row : rows.values()) {
            String entity = row.stream().filter(cell -> PO_KEY_COLUMN.matcher(normalizeColumn(cell.column())).matches())
                    .map(AgenticSqlEvidenceCell::value).filter(value -> value != null).map(this::normalizeText)
                    .findFirst().orElse(null);
            if (entity == null || entity.isBlank()) continue;
            Map<String, Map<String, String>> entityValues = valuesByEntity.computeIfAbsent(entity,
                    ignored -> new HashMap<>());
            for (AgenticSqlEvidenceCell cell : row) {
                String column = normalizeColumn(cell.column());
                if (!COMPARISON_FIELD.matcher(column).matches() || cell.value() == null) continue;
                String normalizedValue = normalizeComparableValue(cell.value());
                Map<String, String> valuesByTable = entityValues.computeIfAbsent(column, ignored -> new HashMap<>());
                for (String table : cell.tables()) {
                    if (valuesByTable.entrySet().stream().anyMatch(entry -> !entry.getKey().equalsIgnoreCase(table)
                            && !entry.getValue().equals(normalizedValue))) return true;
                    valuesByTable.putIfAbsent(table, normalizedValue);
                }
            }
        }
        return false;
    }

    private String normalizeComparableValue(String value) {
        String numeric = normalizeNumber(value);
        return numeric == null ? normalizeText(value) : numeric;
    }

    private static boolean containsComparisonLanguage(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return List.of("最低", "最高", "最便宜", "最昂貴", "最多", "最少", "較低", "較高", "大於", "小於",
                "不高於", "不低於", "lowest", "highest", "cheapest", "most expensive", "greater than",
                "less than").stream().anyMatch(lower::contains);
    }

    private static String expectedComparisonOperator(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (List.of("最低", "最便宜", "最少", "最低價", "較低", "lowest", "cheapest", "less than")
                .stream().anyMatch(lower::contains)) return "MIN";
        if (List.of("最高", "最昂貴", "最多", "最高價", "較高", "highest", "most expensive", "greater than")
                .stream().anyMatch(lower::contains)) return "MAX";
        return null;
    }

    private static String textValue(JsonNode value) {
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String normalizeColumn(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static BigDecimal decimal(String value) {
        if (value == null) return null;
        String normalized = value.replace(",", "").replace("%", "").trim();
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeNumber(String value) {
        BigDecimal number = decimal(value);
        return number == null ? null : number.stripTrailingZeros().toPlainString();
    }

    private record CellKey(String toolCallId, int rowIndex, String column) { }
}
