package com.supplypilot.document.rag;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Requires both retrieval relevance and question-to-source support. Semantic similarity is candidate
 * discovery only; it does not authorize a chunk to answer a question.
 */
@Component
public class EvidenceValidator {

    private static final double MINIMUM_SCORE = 0.15;
    private static final Pattern IDENTIFIER = Pattern.compile("(?i)\\b[A-Z]{2,}[A-Z0-9_]*-[A-Z0-9]+\\b");
    private static final Pattern MATERIAL_IDENTIFIER = Pattern.compile("(?i)\\b(?:MAT|MATERIAL)-[A-Z0-9_]+\\b");
    private static final Pattern EVIDENCE_CLAUSE_BOUNDARY = Pattern.compile(
            "(?<=[。！？!?])\\s*|(?<!\\d)\\.(?!\\d)\\s*|[，,；;\\r\\n]+");
    private static final Pattern EVIDENCE_SENTENCE_BOUNDARY = Pattern.compile(
            "(?<=[。！？!?])\\s*|(?<!\\d)\\.(?!\\d)\\s*|\\r?\\n+");
    private static final Pattern QUESTION_HINT = Pattern.compile("(?i)^(?:問答\\s*[:：]|預期依據\\s*[:：]|q\\s*&\\s*a\\s*[:：])");
    private static final Pattern CONDITION_LEAD = Pattern.compile(
            "(?i)(?:若|如果|假如|倘若|當|凡是|凡發生|\\bif\\b|\\bwhen\\b|\\bunless\\b|"
                    + "\\bprovided\\s+that\\b|\\bin\\s+case\\b)");
    private static final Pattern CONDITION_REFERENCE = Pattern.compile(
            "(?i)(?:這些(?:情況|狀況|條件)|上述(?:情況|狀況|條件)?|以上(?:情況|狀況|條件)?|"
                    + "these\\s+(?:conditions|cases|situations)|those\\s+(?:conditions|cases|situations)|"
                    + "such\\s+(?:conditions|cases|situations)|\\bthey\\b)");
    private static final Pattern SPECIFIC_CONDITION = Pattern.compile(
            "(?i)(?:缺(?:少|失)|不足|破損|損壞|受(?:到)?污染|污染|不符|停機|故障|逾期|遺失|錯誤|失效|洩漏|"
                    + "超(?:出|過)|broken|damaged?|contaminated|incorrect|mismatch(?:ed)?|missing|absent|"
                    + "stops?|stopped|failure|failed|expired|overdue|defective|leak(?:ing)?)");
    private static final Pattern CONDITION_BACKGROUND = Pattern.compile(
            "(?i)(?:依照|依據|根據|按照|依)(?:品質管理|品質)?(?:的)?(?:規定|政策|要求)|"
                    + "according\\s+to\\s+(?:the\\s+)?(?:quality\\s+management\\s+)?(?:policy|rules?|requirements?)|"
                    + "as\\s+required\\s+by\\s+(?:the\\s+)?(?:policy|rules?)|"
                    + "required\\s+by\\s+(?:the\\s+)?policy");
    private static final Pattern GENERIC_CONDITION = Pattern.compile(
            "(?i)(?:異常(?:批次|情況|狀況|事件|問題)?|異常|"
                    + "abnormal(?:\\s+(?:batches?|situations?|conditions?|cases?))?|"
                    + "anomal(?:y|ies)|(?:有|發生|存在|遇到|出現)|"
                    + "(?:this|these|such)\\s+(?:conditions?|situations?|cases?)|"
                    + "(?:情況|狀況|條件))");
    private static final Pattern CONDITION_ACTION = Pattern.compile(
            "(?i)(?:須|必須|(?<!供)應|需要|應該|隔離|通知|聯繫|維修|處理|清除|退回|停用|"
                    + "must|should|need\\s+to|quarantine|isolate|notify|contact|repair|remove|return|disable)");
    private static final Pattern INDEPENDENT_ACTION = Pattern.compile(
            "(?i)(?:通知|聯繫|維修|隔離|處理|清除|退回|停用|更換|停止|"
                    + "notify|contact|repair|quarantine|isolate|handle|remove|return|disable|replace|stop)");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\d])\\d+(?:[.,]\\d+)?");
    private static final Pattern RATE_VALUE = Pattern.compile(
            "(?i)(?:\\d+(?:[.,]\\d+)?\\s*(?:%|百分比)|"
                    + "(?:費率|保險費|保費|rate|premium|fee)\\s*(?:為|是|is|:|：)?\\s*"
                    + "(?:貨值的?\\s*)?\\d+(?:[.,]\\d+)?\\s*(?:%|百分比|元|TWD|USD|NTD))");
    private static final Pattern CURRENCY_VALUE = Pattern.compile(
            "(?i)(?:NT\\$|US\\$|[$€¥]|\\b(?:TWD|USD|NTD|EUR)\\b)\\s*\\d"
                    + "|\\d+(?:[.,]\\d+)?\\s*(?:元|圓)");
    private static final String NUMERIC_VALUE = "\\d+(?:[.,]\\d+)?";
    private static final String DURATION_VALUE = "(?:calendar\\s+days?|business\\s+days?|days?|weeks?|months?|years?|"
            + "hours?|minutes?|個曆日|曆日|工作天|工作日|天|日|週|星期|個月|年|小時|分鐘)";
    private static final Pattern DELIVERY_ASSERTION = Pattern.compile("(?i)(?:標準)?(?:交貨期|交期|"
            + "standard\\s+lead\\s+time|lead\\s+time|delivery\\s+time|delivery)"
            + "[^。！？.!?，,；;\\r\\n]{0,35}?(?:為|是|訂為|定為|requires|is|takes|equals|:|：)"
            + "[^。！？.!?，,；;\\r\\n]{0,30}?" + NUMERIC_VALUE + "\\s*" + DURATION_VALUE);
    private static final Pattern QUOTATION_VALIDITY_ASSERTION = Pattern.compile("(?i)(?:報價|有效期限|有效期|"
            + "quotation\\s+validity|quotation|quote|validity|valid)"
            + "[^。！？.!?，,；;\\r\\n]{0,35}?(?:(?:為|是|valid\\s+for|is\\s+valid\\s+for|lasts\\s+for|:|：)"
            + "[^。！？.!?，,；;\\r\\n]{0,20}?" + NUMERIC_VALUE + "\\s*" + DURATION_VALUE
            + "|[^。！？.!?，,；;\\r\\n]{0,25}?" + NUMERIC_VALUE + "\\s*" + DURATION_VALUE
            + "[^。！？.!?，,；;\\r\\n]{0,10}?(?:內)?有效)");
    private static final Pattern RATE_ASSERTION = Pattern.compile("(?i)(?:保險費率|費率|保費|rate|premium)"
            + "\\s*(?:為|是|is|:|：)\\s*(?:貨值的?\\s*)?" + NUMERIC_VALUE
            + "\\s*(?:%|百分比|元|TWD|USD|NTD)");
    private static final Pattern MONEY_ASSERTION = Pattern.compile("(?i)(?:費用|費|金額|價格|price|cost|fee)"
            + "\\s*(?:為|是|is|:|：)\\s*(?:NT\\$|US\\$|[$€¥]|\\b(?:TWD|USD|NTD|EUR)\\b)?\\s*"
            + NUMERIC_VALUE + "\\s*(?:元|圓)?");
    private static final Set<String> QUESTION_WORDS = Set.of(
            "a", "an", "and", "are", "be", "can", "could", "do", "does", "for", "from", "how",
            "i", "in", "is", "it", "me", "of", "on", "the", "to", "was", "what", "when", "where",
            "which", "who", "why", "would", "you");
    private static final Set<String> CJK_STOP_CHARACTERS = Set.of(
            "的", "了", "是", "有", "中", "哪", "些", "為", "何", "麼", "嗎", "呢", "請", "問", "這",
            "文", "件", "由", "及", "與", "和", "否", "可", "能", "在", "把", "對", "之", "其",
            "我", "們", "你", "他", "她", "它", "個", "種", "情", "狀", "況", "怎", "多", "少", "久", "需", "要");

    /** Query concept aliases support common Chinese/English terminology without supplying answers. */
    private static final List<Topic> TOPICS = List.of(
            new Topic("delivery", "lead time", "delivery time", "standard delivery", "standard lead time",
                    "交期", "交貨期", "標準交期"),
            new Topic("quotation validity", "quotation", "quote", "validity", "valid",
                    "報價", "有效期限", "有效期"),
            new Topic("storage", "storage", "stored", "store", "storage condition",
                    "儲存", "存放", "保存"),
            new Topic("unit", "base unit", "unit", "基本單位", "單位"),
            new Topic("role", "role", "who", "responsible", "operator", "人員", "角色", "誰"),
            new Topic("receiving", "receive", "receiving", "receipt", "收貨", "收取"),
            new Topic("approval", "approve", "approval", "核准"),
            new Topic("quarantine", "isolate", "isolated", "isolation", "quarantine", "quarantined", "隔離"),
            new Topic("payment", "payment", "pay", "payment terms", "付款"),
            new Topic("fees", "fee", "fees", "charge", "charges", "cancellation", "取消費", "費用"),
            new Topic("insurance", "insurance", "保險"),
            new Topic("freight", "freight", "shipping", "sea freight", "海運", "船運"),
            new Topic("frozen", "frozen", "freezing", "冷凍"));

    public boolean isSufficient(String question, List<RetrievedChunk> results) {
        if (results.isEmpty() || results.getFirst().score() < MINIMUM_SCORE) {
            return false;
        }
        Set<String> queryTerms = meaningfulTerms(question);
        if (queryTerms.isEmpty()) return false;
        return results.stream().filter(result -> result.score() >= MINIMUM_SCORE)
                .anyMatch(result -> {
                    Set<String> evidenceTerms = meaningfulTerms(result.chunk().getContent());
                    return evidenceTerms.containsAll(queryTerms)
                            && supportsQuestion(question, result.chunk().getContent());
                });
    }

    public boolean isSemanticallySufficient(String question, List<RetrievedChunk> results, double minimumScore) {
        return !supportingEvidence(question, results, minimumScore).isEmpty();
    }

    public List<RetrievedChunk> supportingEvidence(
            String question, List<RetrievedChunk> results, double minimumScore
    ) {
        return results.stream().filter(result -> result != null && result.chunk() != null
                        && result.score() >= minimumScore && !result.chunk().getContent().isBlank()
                        && supportsQuestion(question, result.chunk().getContent()))
                .toList();
    }

    /** Returns bounded source clauses/sentences that support requested facts, never generated business claims. */
    public String supportedExcerpt(String question, String content) {
        List<Topic> requestedTopics = requestedTopics(question);
        Set<String> identifiers = identifiers(question);
        Set<String> materialIdentifiers = materialIdentifiers(question);
        Set<String> sourceMaterialIdentifiers = materialIdentifiers(content);
        if (!sourceMaterialIdentifiers.containsAll(materialIdentifiers)) return "";
        boolean conditionListQuestion = asksForConditions(question);
        List<String> clauses = conditionListQuestion
                ? supportedConditionWindows(question, content) : splitEvidenceClauses(content);
        List<String> matching = new ArrayList<>();
        boolean quantitative = needsQuantitativeAnswer(question);
        if (!requestedTopics.isEmpty()) {
            for (String clause : clauses) {
                if (isQuestionHint(clause)) continue;
                String normalized = normalize(clause);
                boolean topicMatch = quantitative
                        ? requestedTopics.stream().allMatch(topic -> topic.matches(normalized))
                        : requestedTopics.stream().anyMatch(topic -> topic.matches(normalized));
                boolean materialMatch = materialIdentifiers.isEmpty()
                        || sourceMaterialIdentifiers.size() <= 1
                        || materialIdentifiers.stream().allMatch(id -> normalized.contains(id.toLowerCase(Locale.ROOT)));
                if (topicMatch && materialMatch && (!quantitative || containsSupportedValue(question, List.of(clause)))) {
                    matching.add(clause.trim());
                }
            }
        } else {
            Set<String> queryTerms = meaningfulTerms(question);
            for (String clause : clauses) {
                Set<String> sentenceTerms = meaningfulTerms(clause);
                long overlap = queryTerms.stream().filter(sentenceTerms::contains).count();
                if (hasEnoughLexicalSupport(queryTerms, overlap)) matching.add(clause.trim());
            }
        }
        String excerpt = matching.stream().filter(value -> !value.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining(" "));
        if (excerpt.isBlank() || !containsAllIdentifiers(content, identifiers)) return "";
        if (!containsAllTopics(question, matching)) return "";
        if (needsQuantitativeAnswer(question) && !containsSupportedValue(question, matching)) return "";
        return excerpt;
    }

    private boolean supportsQuestion(String question, String content) {
        String normalizedContent = normalize(content);
        List<Topic> requestedTopics = requestedTopics(question);
        if (!containsAllIdentifiers(normalizedContent, identifiers(question))) return false;
        if (!requestedTopics.isEmpty()) {
            if (requestedTopics.stream().anyMatch(topic -> !topic.matches(normalizedContent))) return false;
            String excerpt = supportedExcerpt(question, content);
            return !excerpt.isBlank();
        }
        Set<String> terms = meaningfulTerms(question);
        long overlap = terms.stream().filter(term -> meaningfulTerms(content).contains(term)).count();
        return hasEnoughLexicalSupport(terms, overlap) && !supportedExcerpt(question, content).isBlank();
    }

    private boolean hasEnoughLexicalSupport(Set<String> queryTerms, long overlap) {
        if (queryTerms.isEmpty()) return false;
        int required = queryTerms.size() == 1 ? 1 : Math.max(2, (int) Math.ceil(queryTerms.size() * 0.5));
        return overlap >= required;
    }

    private Set<String> meaningfulTerms(String text) {
        Set<String> terms = new HashSet<>();
        for (String term : LocalLexicalEmbeddingProvider.tokens(text)) {
            if (term.length() > 1 && !QUESTION_WORDS.contains(term)) terms.add(term);
            else if (term.length() == 1 && term.charAt(0) >= 0x4E00 && !CJK_STOP_CHARACTERS.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private List<Topic> requestedTopics(String question) {
        String normalized = normalize(question);
        return TOPICS.stream().filter(topic -> topic.matches(normalized)).toList();
    }

    private Set<String> identifiers(String question) {
        Matcher matcher = IDENTIFIER.matcher(question);
        Set<String> result = new HashSet<>();
        while (matcher.find()) result.add(matcher.group().toUpperCase(Locale.ROOT));
        return result;
    }

    private Set<String> materialIdentifiers(String text) {
        Matcher matcher = MATERIAL_IDENTIFIER.matcher(text);
        Set<String> result = new HashSet<>();
        while (matcher.find()) result.add(matcher.group().toUpperCase(Locale.ROOT));
        return result;
    }

    private boolean needsQuantitativeAnswer(String question) {
        String normalized = normalize(question);
        return containsAny(normalized, "多少", "幾", "多久", "費率", "保費", "rate", "premium",
                "how much", "how many", "how long", "what is the fee", "what is the cost");
    }

    private boolean containsAllTopics(String question, List<String> sentences) {
        List<Topic> requested = requestedTopics(question);
        return requested.stream().allMatch(topic -> sentences.stream()
                .anyMatch(sentence -> topic.matches(normalize(sentence))));
    }

    private boolean containsSupportedValue(String question, List<String> sentences) {
        String normalizedQuestion = normalize(question);
        boolean rate = containsAny(normalizedQuestion, "費率", "rate", "premium");
        boolean money = rate || containsAny(normalizedQuestion, "費用", "費", "金額", "price", "cost", "fee", "多少錢");
        boolean duration = containsAny(normalizedQuestion, "多久", "幾天", "交期", "有效期限", "how long");
        boolean validity = containsAny(normalizedQuestion, "報價", "quotation", "quote", "有效期限", "validity", "valid");
        boolean delivery = containsAny(normalizedQuestion, "交期", "交貨期", "lead time", "delivery", "how long")
                && !validity;
        return sentences.stream().anyMatch(clause -> {
            if (!NUMBER.matcher(clause).find()) return false;
            if (rate) return RATE_VALUE.matcher(clause).find() && RATE_ASSERTION.matcher(clause).find();
            if (money) return CURRENCY_VALUE.matcher(clause).find() && MONEY_ASSERTION.matcher(clause).find();
            if (validity) return QUOTATION_VALIDITY_ASSERTION.matcher(clause).find();
            if (duration && delivery) return DELIVERY_ASSERTION.matcher(clause).find();
            return false;
        });
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private boolean containsAllIdentifiers(String content, Set<String> identifiers) {
        String normalized = normalize(content);
        return identifiers.stream().allMatch(identifier -> normalized.contains(identifier.toLowerCase(Locale.ROOT)));
    }

    private List<String> splitEvidenceClauses(String content) {
        return List.of(EVIDENCE_CLAUSE_BOUNDARY.split(content)).stream()
                .filter(clause -> !isQuestionHint(clause)).toList();
    }

    private List<String> supportedConditionWindows(String question, String content) {
        List<String> sentences = List.of(EVIDENCE_SENTENCE_BOUNDARY.split(content)).stream()
                .map(String::trim).filter(sentence -> !sentence.isBlank() && !isQuestionHint(sentence)).toList();
        List<Topic> topics = requestedTopics(question);
        List<String> supported = new ArrayList<>();
        for (int index = 0; index < sentences.size(); index++) {
            String sentence = sentences.get(index);
            for (String clause : splitConditionClauses(sentence)) {
                if (supportsListQuestion(question, clause, topics) && hasConditionInformation(clause)) {
                    supported.add(clause);
                }
            }

            if (supportsListQuestion(question, sentence, topics) && index > 0
                    && CONDITION_REFERENCE.matcher(sentence).find()
                    && hasConditionInformation(sentences.get(index - 1))) {
                supported.add(sentences.get(index - 1) + " " + sentence);
            }
            if (supportsListQuestion(question, sentence, topics) && index > 0
                    && endsWithEnumerationContinuation(sentences.get(index - 1))) {
                int start = index - 1;
                while (start > 0 && endsWithEnumerationContinuation(sentences.get(start - 1))) start--;
                String continuation = String.join(" ", sentences.subList(start, index + 1));
                if (hasConditionInformation(continuation)) supported.add(continuation);
            }
            if (index + 1 < sentences.size()) {
                String nextSentence = sentences.get(index + 1);
                if (hasConditionInformation(sentence) && CONDITION_REFERENCE.matcher(nextSentence).find()
                        && supportsListQuestion(question, nextSentence, topics)) {
                    supported.add(sentence + " " + nextSentence);
                }
            }
        }
        return supported.stream().distinct().toList();
    }

    private List<String> splitConditionClauses(String sentence) {
        List<String> clauses = List.of(sentence.split("(?<=[;；])|(?<=[,，])\\s*(?=(?:(?:and|or)\\s+)?"
                        + "(?:若|如果|假如|倘若|當|凡是|凡發生|if\\b|when\\b|unless\\b))"))
                .stream().map(String::trim).map(clause -> clause.replaceFirst("(?i)^(?:and|or)\\s+", ""))
                .filter(clause -> !clause.isBlank()).toList();
        if (clauses.size() < 2) return clauses;

        List<String> grouped = new ArrayList<>();
        String pendingCondition = "";
        for (String clause : clauses) {
            String current = pendingCondition.isBlank() ? clause : pendingCondition + " " + clause;
            if (hasConditionLead(clause) && hasConditionInformation(current)
                    && INDEPENDENT_ACTION.matcher(clause).find()) {
                grouped.add(current);
                pendingCondition = "";
            } else if (hasConditionLead(clause) && !INDEPENDENT_ACTION.matcher(clause).find()) {
                pendingCondition = current;
            } else {
                if (!pendingCondition.isBlank()) grouped.add(pendingCondition);
                pendingCondition = "";
                grouped.add(clause);
            }
        }
        if (!pendingCondition.isBlank()) grouped.add(pendingCondition);
        return grouped;
    }

    private boolean asksForConditions(String question) {
        String normalized = normalize(question);
        return normalized.matches(".*(?:哪些|哪種|何種|什麼).*(?:情況|狀況|情形|條件|原因|時候|異常).*|"
                + ".*(?:which|what)\\s+(?:conditions?|circumstances?|situations?|cases?).*|"
                + "^when\\s+should\\b.*");
    }

    private boolean supportsListQuestion(String question, String sentence, List<Topic> topics) {
        if (topics.stream().anyMatch(topic -> topic.matches(normalize(sentence)))) return true;
        if (!topics.isEmpty()) return false;
        Set<String> queryTerms = meaningfulTerms(question);
        Set<String> sentenceTerms = meaningfulTerms(sentence);
        long overlap = queryTerms.stream().filter(sentenceTerms::contains).count();
        return hasEnoughLexicalSupport(queryTerms, overlap);
    }

    private boolean hasConditionInformation(String sentence) {
        Matcher lead = CONDITION_LEAD.matcher(sentence);
        if (!lead.find()) return false;
        String afterLead = sentence.substring(lead.end());
        Matcher action = CONDITION_ACTION.matcher(afterLead);
        String antecedent = action.find() ? afterLead.substring(0, action.start()) : afterLead;
        if (SPECIFIC_CONDITION.matcher(antecedent).find()) return true;

        String substantiveCondition = CONDITION_BACKGROUND.matcher(antecedent).replaceAll(" ");
        substantiveCondition = GENERIC_CONDITION.matcher(substantiveCondition).replaceAll(" ");
        substantiveCondition = substantiveCondition.replaceAll("(?i)\\b(?:the|a|an|is|are|was|were)\\b", " ")
                .replaceAll("[^\\p{L}\\p{N}]", "");
        // Separators are structural only: policy preambles and generic abnormality wording must
        // reduce to no substantive facts, while an unlisted concrete condition (e.g. supplier delay)
        // remains sufficient even without a separator.
        return substantiveCondition.length() >= 2;
    }

    private boolean hasConditionLead(String sentence) {
        return CONDITION_LEAD.matcher(sentence).find();
    }

    private boolean endsWithEnumerationContinuation(String sentence) {
        String trimmed = sentence.stripTrailing();
        return trimmed.matches(".*(?:、|，|,|;|；|或|及|和|\\band|\\bor)$");
    }

    private boolean isQuestionHint(String clause) {
        return QUESTION_HINT.matcher(clause.stripLeading()).find();
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private record Topic(String name, List<String> synonyms) {
        private Topic(String name, String... synonyms) {
            this(name, List.of(synonyms));
        }

        private boolean matches(String normalizedText) {
            return synonyms.stream().anyMatch(synonym -> {
                if (synonym.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00)) {
                    return normalizedText.contains(synonym);
                }
                String pattern = "(?<![a-z0-9])" + Pattern.quote(synonym) + "(?![a-z0-9])";
                return Pattern.compile(pattern).matcher(normalizedText).find();
            });
        }
    }
}
