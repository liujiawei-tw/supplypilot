package com.supplypilot.ai.tool;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.supplypilot.ai.dto.AiToolType;

/** Shared, conservative classification of facts explicitly requested by an AI query. */
public final class AiQuestionIntent {
    private static final Pattern DOCUMENT_TERM = Pattern.compile("(?i)(?:供貨協議|付款條件|原文|引用|合約|契約|條款|文件|規範|採購\\s*SOP|"
            + "標準交期|交貨期|報價有效|有效期限|基本單位|儲存條件|儲存方式|隔離(?:批次|條件)|保險費率|"
            + "contract|quotation\\s+validity|standard\\s+lead\\s+time|standard\\s+delivery|"
            + "storage\\s+conditions?|quarantine\\s+conditions?|insurance\\s+(?:rate|premium)|"
            + "(?:purchase|procurement)\\s+sop)");
    private static final Pattern INVENTORY_TERM = Pattern.compile("(?i)(?:庫存|存量|還剩多少|inventory|stock\\s*(?:level|quantity)?)");
    private static final Pattern AVERAGE_PRICE_TERM = Pattern.compile("(?i)(?:平均(?:單價|價格|報價)?|average\\s+(?:unit\\s+)?price)");
    private static final Pattern PRICE_TERM = Pattern.compile("(?i)(?:單價|報價|價格|price|quotation|quote)");
    private static final Pattern SUPPLIER_TERM = Pattern.compile("(?i)(?:供應商|supplier|可靠度|reliability|品質評分|quality\\s+score|評價|評分|rating|score)");
    private static final Pattern SUPPLIER_STATUS_TERM = Pattern.compile("(?i)(?:狀態|啟用|停用|active|inactive)");
    private static final Pattern SUPPLIER_CODE = Pattern.compile("(?i)(?<![A-Z0-9_])SUP(?:PLIER)?-[A-Z0-9]+(?![A-Z0-9_-])");
    private static final Pattern RECOMMENDATION_TERM = Pattern.compile("(?i)(?:採購建議|推薦|recommendation|recommended|"
            + "建議\\s*(?:採購(?:數量|量)?|數量|買(?:多少)?)|suggested\\s+(?:purchase\\s+)?(?:quantity|amount))");
    private static final Pattern RANKING_TERM = Pattern.compile("(?i)(?:供應商排名|供應商排序|supplier\\s+ranking|supplier\\s+rank)");
    private static final Pattern PO_NUMBER = Pattern.compile("(?i)\\bPO-[A-Z0-9]+-[A-Z0-9]+\\b");
    private static final Pattern OPEN_PO_TERM = Pattern.compile("(?i)(?:未收貨(?:採購單)?|還沒收貨|尚未收貨|尚未收到|未到貨|未完成採購單|在途(?:採購單)?|open\\s+(?:po|purchase\\s+orders?))");

    private AiQuestionIntent() {
    }

    public static Set<AiToolType> requestedTools(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        EnumSet<AiToolType> requested = EnumSet.noneOf(AiToolType.class);
        if (DOCUMENT_TERM.matcher(normalized).find()) {
            requested.add(AiToolType.SEARCH_DOCUMENTS);
            if (RECOMMENDATION_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_RECOMMENDATION);
            if (RANKING_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_SUPPLIER_RANKING);
            return Set.copyOf(requested);
        }
        if (PO_NUMBER.matcher(normalized).find()) {
            requested.add(AiToolType.QUERY_PURCHASE_ORDER);
            return Set.copyOf(requested);
        }
        boolean openPurchaseOrders = OPEN_PO_TERM.matcher(normalized).find();
        if (INVENTORY_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_INVENTORY);
        boolean average = AVERAGE_PRICE_TERM.matcher(normalized).find();
        boolean price = PRICE_TERM.matcher(normalized).find();
        if (average) requested.add(AiToolType.QUERY_AVERAGE_PRICE);
        else if (price) requested.add(AiToolType.QUERY_SUPPLIER_QUOTES);
        boolean supplierQuery = SUPPLIER_TERM.matcher(normalized).find()
                || SUPPLIER_CODE.matcher(normalized).find() && SUPPLIER_STATUS_TERM.matcher(normalized).find();
        if (supplierQuery && !price && !average && !openPurchaseOrders) requested.add(AiToolType.QUERY_SUPPLIER);
        if (openPurchaseOrders) requested.add(AiToolType.QUERY_OPEN_PO);
        if (RECOMMENDATION_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_RECOMMENDATION);
        if (RANKING_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_SUPPLIER_RANKING);
        return Set.copyOf(requested);
    }

    public static boolean requiresRecommendation(String question) {
        return requestedTools(question).contains(AiToolType.QUERY_RECOMMENDATION);
    }

    public static boolean satisfies(AiToolType requested, AiToolType executed) {
        return requested == executed || requested == AiToolType.QUERY_RECOMMENDATION
                && executed == AiToolType.EXPLAIN_RECOMMENDATION;
    }
}
