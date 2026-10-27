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
    private static final Map<String, List<String>> ASSERTION_FAMILIES = Map.of(
            "FINANCIAL_CONDITION", List.of("破產", "倒閉", "bankrupt", "bankruptcy", "insolvent", "insolvency"),
            "DELIVERY_RELIABILITY", List.of("交期可靠", "交付可靠", "交期不穩", "遲交", "late delivery",
                    "不可靠", "可靠", "不穩定", "穩定", "reliable", "unreliable", "unstable", "stable",
                    "reliable delivery", "unreliable delivery", "delivery is reliable", "on time delivery"),
            "QUALITY", List.of("品質良好", "品質優良", "品質不佳", "品質差", "good quality", "poor quality",
                    "defective", "defect rate"),
            "PRICE_COMPARISON", List.of("最低價", "最高價", "報價最低", "報價最高", "最便宜", "最昂貴",
                    "較低", "較高", "lowest", "highest", "cheapest", "most expensive"),
            "RISK", List.of("風險", "高風險", "risk", "high risk"),
            "RECOMMENDATION", List.of("建議", "應該", "暫停合作", "停止合作", "recommend", "should", "suspend"),
            "CAUSAL", List.of("導致", "造成", "因為", "caused", "causes", "leads to"));
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
                boolean factsPresent = cited != null && containsClaimFacts(text, cited);
                boolean factSupported = cited != null && supportedFact(text, claim.get("fact"), claim.get("comparison"), cited, cells,
                        comparisonValid);
                boolean assertionsSupported = cited != null && assertionsSupported(text, claim.get("fact"), cited, comparisonValid);
                if (cited == null || !factsPresent || !comparisonValid || !factSupported || !assertionsSupported) {
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

    private boolean supportedFact(String claimText, JsonNode fact, JsonNode comparison,
                                  List<AgenticSqlEvidenceCell> cited,
                                  Map<CellKey, AgenticSqlEvidenceCell> cells,
                                  boolean comparisonValid) {
        if (fact == null || !fact.isObject()) return false;
        String kind = textValue(fact.get("kind"));
        String attribute = textValue(fact.get("attribute"));
        AgenticSqlEvidenceCell value = resolveReference(fact.get("value"), cells);
        boolean attrMatches = value != null && attributeMatches(attribute, kind, value.sourceColumns());
        boolean textHasValue = value != null && containsValue(claimText, value.value());
        if (kind == null || attribute == null || value == null || !cited.contains(value)
                || !attrMatches || !textHasValue) return false;
        if ("DERIVED".equals(kind) && !explicitlyQualifiedCalculation(claimText)) return false;

        JsonNode subjectReference = fact.get("subject");
        if (!"AGGREGATE".equals(kind) && (subjectReference == null || subjectReference.isNull())) return false;
        if (subjectReference != null && !subjectReference.isNull()) {
            AgenticSqlEvidenceCell subject = resolveReference(subjectReference, cells);
            if (subject == null || !cited.contains(subject) || !sameResultRow(subject, value)
                    || !containsEntityValue(claimText, subject)) return false;
        }

        if ("COMPARISON".equals(kind)) {
            return comparisonValid && comparison != null && comparison.isObject()
                    && value.equals(resolveReference(comparison.get("target"), cells));
        }
        if ("INFERENCE".equals(kind)) {
            return explicitlyQualifiedInference(claimText);
        }
        return "FIELD_VALUE".equals(kind) || "AGGREGATE".equals(kind) || "DERIVED".equals(kind);
    }

    private boolean attributeMatches(String attribute, String kind, Set<String> sources) {
        String expected = columnLeaf(attribute);
        if (expected.isBlank()) return false;
        String derivedNumeric = markerExpression(sources, "@derived-numeric:");
        if (sources.stream().anyMatch(source -> source.startsWith("@transformed:")
                || source.startsWith("@derived-aggregate:"))) return false;
        if (derivedNumeric != null) {
            return "DERIVED".equals(kind)
                    && normalizeExpression(attribute).equals(normalizeExpression(derivedNumeric));
        }
        if ("DERIVED".equals(kind)) return false;
        boolean aggregate = sources.stream().anyMatch(source -> source.startsWith("@aggregate:"));
        if (aggregate != "AGGREGATE".equals(kind)) return false;
        for (String source : sources) {
            if ("AGGREGATE".equals(kind) && source.startsWith("@aggregate:")) {
                String[] parts = source.split(":", 3);
                if (parts.length < 2 || !expected.startsWith(parts[1].toLowerCase(Locale.ROOT))) continue;
                if (parts.length == 2 || parts[2].startsWith("@rows:")) return true;
                String expectedField = expected.substring(parts[1].length()).replaceAll("^[^a-z0-9]+", "");
                if (expectedField.isBlank()) return true;
                for (String input : parts[2].split(",")) {
                    String leaf = columnLeaf(input);
                    if (!leaf.isBlank() && expectedField.contains(leaf)) return true;
                }
                continue;
            }
            String leaf = columnLeaf(source);
            if (leaf.equals(expected) || leaf.endsWith("_" + expected)) return true;
        }
        return false;
    }

    private String markerExpression(Set<String> sources, String prefix) {
        return sources.stream().filter(source -> source.startsWith(prefix))
                .map(source -> source.substring(prefix.length())).findFirst().orElse(null);
    }

    private String normalizeExpression(String value) {
        return value == null ? "" : value.replace("`", "").replace("\"", "")
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String columnLeaf(String value) {
        if (value == null) return "";
        return normalizeColumn(value.substring(value.lastIndexOf('.') + 1));
    }

    private boolean sameResultRow(AgenticSqlEvidenceCell left, AgenticSqlEvidenceCell right) {
        return left.toolCallId().equals(right.toolCallId()) && left.rowIndex() == right.rowIndex();
    }

    private boolean containsValue(String claim, String value) {
        if (claim == null || value == null || value.isBlank()) return false;
        String numeric = normalizeNumber(value);
        if (numeric != null) return claimNumbers(claim).contains(numeric);
        return normalizeText(claim).contains(normalizeText(value));
    }

    private boolean containsEntityValue(String claim, AgenticSqlEvidenceCell subject) {
        if (containsValue(claim, subject.value())) return true;
        boolean supplierName = subject.sourceColumns().stream().map(AgenticSqlAnswerSupportVerifier::columnLeaf)
                .anyMatch(column -> column.contains("supplier") || column.contains("vendor"));
        if (!supplierName) return false;
        String sourceName = normalizeText(subject.value());
        String entityToken = sourceName.replaceFirst("^(?:supplier|vendor|company)", "");
        String normalizedClaim = normalizeText(claim);
        boolean supplierMarker = normalizedClaim.contains("供應商") || normalizedClaim.contains("廠商")
                || normalizedClaim.contains("supplier") || normalizedClaim.contains("vendor");
        return supplierMarker && !entityToken.isBlank() && normalizedClaim.contains(entityToken);
    }

    private Set<String> claimNumbers(String claim) {
        Set<String> numbers = new HashSet<>();
        Matcher matcher = NUMBER.matcher(claim);
        while (matcher.find()) {
            String normalized = normalizeNumber(matcher.group());
            if (normalized != null) numbers.add(normalized);
        }
        return numbers;
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
        if (cell == null || cell.value() == null || !cell.sourceBacked() || cell.sourceColumns().isEmpty()
                || MASKED_VALUES.contains(cell.value())
                || cell.sourceColumns().stream().anyMatch(source -> source.startsWith("@transformed:")
                        || source.startsWith("@derived-aggregate:"))) return null;
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

    /** Requires a typed, source-column-aligned proposition; citations alone never validate prose. */
    private boolean assertionsSupported(String claim, JsonNode fact,
                                        List<AgenticSqlEvidenceCell> cited, boolean comparisonValid) {
        String kind = textValue(fact == null ? null : fact.get("kind"));
        for (Map.Entry<String, List<String>> family : ASSERTION_FAMILIES.entrySet()) {
            List<String> assertedPhrases = family.getValue().stream()
                    .filter(phrase -> containsPhrase(claim, phrase)).toList();
            if (assertedPhrases.isEmpty()) continue;
            if ("DERIVED".equals(kind)) return false;
            boolean direct = cited.stream().anyMatch(cell -> assertedPhrases.stream()
                    .anyMatch(phrase -> containsPhrase(cell.value(), phrase)));
            if (direct) continue;
            if ("PRICE_COMPARISON".equals(family.getKey()) && comparisonValid
                    && hasSourceRole(cited, "PRICE")) continue;
            if (!"INFERENCE".equals(kind) || !explicitlyQualifiedInference(claim)
                    || !familyHasRelevantSource(family.getKey(), cited, comparisonValid)) return false;
        }
        return true;
    }

    private boolean familyHasRelevantSource(String family, List<AgenticSqlEvidenceCell> cited,
                                            boolean comparisonValid) {
        if ("RECOMMENDATION".equals(family) || "CAUSAL".equals(family)) {
            return cited.stream().flatMap(cell -> cell.sourceColumns().stream()).distinct().count() >= 2;
        }
        if ("PRICE_COMPARISON".equals(family)) return comparisonValid && hasSourceRole(cited, "PRICE");
        String role = switch (family) {
            case "FINANCIAL_CONDITION" -> "FINANCIAL";
            case "DELIVERY_RELIABILITY" -> "DELIVERY";
            case "QUALITY" -> "QUALITY";
            case "RISK" -> "RISK";
            default -> "";
        };
        return !role.isEmpty() && hasSourceRole(cited, role);
    }

    private boolean hasSourceRole(List<AgenticSqlEvidenceCell> cited, String role) {
        return cited.stream().flatMap(cell -> cell.sourceColumns().stream())
                .anyMatch(source -> sourceRole(source).equals(role));
    }

    private String sourceRole(String source) {
        String column = normalizeColumn(source.substring(source.lastIndexOf('.') + 1));
        if (column.contains("price") || column.contains("cost") || column.contains("amount")
                || column.contains("discount") || column.contains("quote") || column.contains("報價")
                || column.contains("價格") || column.contains("成本")) return "PRICE";
        if (column.contains("delivery") || column.contains("leadtime") || column.contains("shipment")
                || column.contains("ontime") || column.contains("late") || column.contains("交期")
                || column.contains("交貨") || column.contains("遲交")) return "DELIVERY";
        if (column.contains("quality") || column.contains("defect") || column.contains("inspection")
                || column.contains("品質") || column.contains("瑕疵")) return "QUALITY";
        if (column.contains("financial") || column.contains("solvency") || column.contains("bankrupt")
                || column.contains("insolvency") || column.contains("creditstatus") || column.contains("財務")
                || column.contains("信用狀態")) return "FINANCIAL";
        if (column.contains("risk") || column.contains("evaluation") || column.contains("rating")
                || column.contains("score") || column.contains("風險") || column.contains("評估")
                || column.contains("評分")) return "RISK";
        return "OTHER";
    }

    private boolean explicitlyQualifiedInference(String claim) {
        return List.of("推測", "推論", "可能", "或許", "初步判斷", "據此推測", "inference", "infer",
                        "may", "might", "could", "possibly")
                .stream().anyMatch(phrase -> containsPhrase(claim, phrase));
    }

    private boolean explicitlyQualifiedCalculation(String claim) {
        String lower = claim == null ? "" : claim.toLowerCase(Locale.ROOT);
        return List.of("計算", "運算", "computed", "calculated", "calculation", "derived value")
                .stream().anyMatch(lower::contains);
    }

    private boolean containsPhrase(String text, String phrase) {
        if (text == null || phrase == null || phrase.isBlank()) return false;
        if (phrase.codePoints().anyMatch(codePoint -> codePoint > 127)) {
            String normalizedText = normalizeText(text);
            String normalizedPhrase = normalizeText(phrase);
            int at = normalizedText.indexOf(normalizedPhrase);
            if (at < 0) return false;
            if (("可靠".equals(normalizedPhrase) || "穩定".equals(normalizedPhrase))
                    && at > 0 && normalizedText.charAt(at - 1) == '不') return false;
            return true;
        }
        Pattern pattern = Pattern.compile("(?i)(?<![a-z])" + Pattern.quote(phrase) + "(?![a-z])");
        return pattern.matcher(text).find();
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
