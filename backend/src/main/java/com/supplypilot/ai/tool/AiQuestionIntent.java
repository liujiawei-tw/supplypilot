package com.supplypilot.ai.tool;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.supplypilot.ai.dto.AiToolType;

/** Shared, conservative classification of facts explicitly requested by an AI query. */
public final class AiQuestionIntent {
    private static final Pattern DOCUMENT_TERM = Pattern.compile("(?i)(?:合約|契約|條款|文件|規範|採購\\s*SOP|"
            + "標準交期|交貨期|報價有效|有效期限|基本單位|儲存條件|儲存方式|隔離(?:批次|條件)|保險費率|"
            + "contract|quotation\\s+validity|standard\\s+lead\\s+time|standard\\s+delivery|"
            + "storage\\s+conditions?|quarantine\\s+conditions?|insurance\\s+(?:rate|premium)|"
            + "(?:purchase|procurement)\\s+sop)");
    private static final Pattern RECOMMENDATION_TERM = Pattern.compile("(?i)(?:採購建議|推薦|recommendation|recommended)");
    private static final Pattern RANKING_TERM = Pattern.compile("(?i)(?:供應商排名|供應商排序|supplier\\s+ranking|supplier\\s+rank)");
    private static final Pattern INVENTORY_TERM = Pattern.compile("(?i)(?:庫存|存量|inventory|stock\\s+(?:level|quantity))");
    private static final Pattern OPEN_PO_TERM = Pattern.compile("(?i)(?:未收貨(?:採購單)?|未完成採購單|在途(?:採購單)?|open\\s+(?:po|purchase\\s+orders?))");
    private static final Set<AiToolType> RECOMMENDATION_TOOLS = Set.of(
            AiToolType.QUERY_RECOMMENDATION, AiToolType.EXPLAIN_RECOMMENDATION,
            AiToolType.QUERY_SUPPLIER_RANKING);

    private AiQuestionIntent() {
    }

    public static Set<AiToolType> requestedTools(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        EnumSet<AiToolType> requested = EnumSet.noneOf(AiToolType.class);
        if (DOCUMENT_TERM.matcher(normalized).find()) requested.add(AiToolType.SEARCH_DOCUMENTS);
        if (RECOMMENDATION_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_RECOMMENDATION);
        if (RANKING_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_SUPPLIER_RANKING);
        if (INVENTORY_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_INVENTORY);
        if (OPEN_PO_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_OPEN_PO);
        return Set.copyOf(requested);
    }

    public static boolean requiresRecommendation(String question) {
        return requestedTools(question).stream().anyMatch(RECOMMENDATION_TOOLS::contains);
    }

    public static boolean satisfies(AiToolType requested, AiToolType executed) {
        return requested == executed || requested == AiToolType.QUERY_RECOMMENDATION
                && executed == AiToolType.EXPLAIN_RECOMMENDATION;
    }
}
