package com.supplypilot.ai.tool;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class AiQueryPlanner {
    private static final Pattern MATERIAL_CODE = Pattern.compile("(?i)(?<![A-Z0-9_])([A-Z]{2,}[A-Z0-9_]*-[A-Z0-9]+)(?![A-Z0-9_-])");
    private static final Pattern SUPPLIER_CODE = Pattern.compile("(?i)(?<![A-Z0-9_])SUP(?:PLIER)?-[A-Z0-9]+(?![A-Z0-9_-])");
    private static final Pattern PURCHASE_ORDER_NUMBER = Pattern.compile("(?i)(?<![A-Z0-9_])PO-[A-Z0-9]+-[A-Z0-9]+(?![A-Z0-9_-])");
    private static final Pattern RECOMMENDATION_ID = Pattern.compile(
            "(?i)(?:recommendation\\s*(?:id|#)|建議(?:編號| ID))\\s*[:#]?\\s*(\\d+)");
    private static final Pattern FORBIDDEN = Pattern.compile("(?i)(?:"
            + "\\b(?:user\\s+management|master\\s+data\\s+write)\\b|使用者管理|主檔寫入"
            + "|忽略.{0,20}(?:規則|指示).{0,30}(?:approve|reject|order|receive|核准|駁回|下單|收貨)"
            + "|^\\s*(?:please\\s+)?(?:ignore\\s+instructions\\s+and\\s+)?"
            + "(?:approve|reject|order\\s+(?:po|purchase\\s+order)|"
            + "receive\\s+(?:po|purchase\\s+order)|adjust\\s+inventory|create\\s+(?:a\\s+)?(?:purchase\\s+)?draft)\\b"
            + "|^\\s*(?:下單|調整庫存(?:數量)?|建立(?:採購單?|採購)?草稿|核准(?:這張|採購單|PO)|駁回(?:這張|採購單|PO))"
            + "|^\\s*(?:執行|操作|進行)(?:採購單|PO)?(?:收貨|核准|駁回|下單|調整庫存|建立(?:採購)?草稿)"
            + "|^\\s*(?:在.{0,12})?(?:幫我|替我)(?:把|將)?(?:這張|這筆|此)?(?:採購單|PO|庫存(?:數量)?|採購)?"
            + "(?:直接|立即)?(?:執行|操作|進行)?(?:收貨|核准|駁回|下單|調整庫存|建立(?:採購)?草稿)"
            + "|^\\s*(?:立即|直接).{0,20}(?:標記|設為|改為|轉為).{0,20}\\b(?:ORDERED|RECEIVED|APPROVED|REJECTED)\\b"
            + "|^\\s*(?:please\\s+)?(?:execute|perform|carry\\s+out)\\s+(?:the\\s+)?"
            + "(?:receiv(?:e|ing)|approval|reject(?:ion)?|order(?:ing)?|inventory\\s+adjustment)\\b"
            + "|^\\s*(?:please\\s+)?(?:can|could|would)\\s+you\\s+(?:please\\s+)?"
            + "(?:(?:help\\s+me|assist\\s+me\\s+to?)\\s+)?"
            + "(?:approve|reject|receive|order|place\\s+(?:an?\\s+)?order|adjust\\s+inventory|"
            + "mark.{0,30}\\b(?:ORDERED|RECEIVED|APPROVED)\\b)\\b"
            + "|^\\s*(?:please\\s+)?(?:help\\s+me|assist\\s+me\\s+to?)\\s+"
            + "(?:approve|reject|receive|order|place\\s+(?:an?\\s+)?order|adjust\\s+inventory)\\b"
            + "|^\\s*(?:please\\s+)?(?:help\\s+me|do\\s+it\\s+for\\s+me|can\\s+you|could\\s+you|would\\s+you)\\s+"
            + "(?:please\\s+)?(?:approve|reject|place\\s+(?:an?\\s+)?order|order\\s+(?:po|purchase\\s+order)|"
            + "receive\\s+(?:po|purchase\\s+order)|adjust\\s+inventory|create\\s+(?:a\\s+)?(?:purchase\\s+)?draft)\\b"
            + "|^\\s*(?:請\\s*)?(?:把|將).{0,50}(?:核准|批准|駁回|收貨|下單|"
            + "標記為\\s*(?:ORDERED|RECEIVED|APPROVED|REJECTED)|調整庫存)\\s*(?:一下|一次)?[。！？.!?]*\\s*$"
            + "|^\\s*(?:請\\s*)?(?:替我|幫我).{0,60}(?:完成|執行|進行|操作|處理)"
            + ".{0,40}(?:核准|批准|駁回|收貨|下單|調整庫存|建立(?:採購)?草稿)"
            + "\\s*(?:一下|一次)?[。！？.!?]*\\s*$"
            + "|^\\s*(?:請(?!問)(?:你)?|幫我|替我|立即|直接)(?:立即|直接)?(?:幫我|替我)?"
            + "(?:執行|操作|進行)?(?:收貨|核准|駁回|下單|調整庫存|建立(?:採購)?草稿)"
            + "|(?:幫我|替我)(?:立即|直接)?(?:執行|操作|進行)?"
            + "(?:收貨|核准|駁回|下單|調整庫存|建立(?:採購)?草稿))");

    public AiQueryPlan plan(String message) {
        String normalized = validate(message);
        var requested = AiQuestionIntent.requestedTools(normalized);
        AiToolType tool = requested.contains(AiToolType.SEARCH_DOCUMENTS) ? AiToolType.SEARCH_DOCUMENTS
                : requested.contains(AiToolType.QUERY_PURCHASE_ORDER) ? AiToolType.QUERY_PURCHASE_ORDER
                : requested.contains(AiToolType.QUERY_SUPPLIER_RANKING) ? AiToolType.QUERY_SUPPLIER_RANKING
                : requested.contains(AiToolType.QUERY_RECOMMENDATION)
                    ? containsAny(normalized.toLowerCase(Locale.ROOT), "why", "explain", "為什麼", "原因")
                        ? AiToolType.EXPLAIN_RECOMMENDATION : AiToolType.QUERY_RECOMMENDATION
                : requested.contains(AiToolType.QUERY_INVENTORY) ? AiToolType.QUERY_INVENTORY
                : requested.contains(AiToolType.QUERY_AVERAGE_PRICE) ? AiToolType.QUERY_AVERAGE_PRICE
                : requested.contains(AiToolType.QUERY_SUPPLIER_QUOTES) ? AiToolType.QUERY_SUPPLIER_QUOTES
                : requested.contains(AiToolType.QUERY_SUPPLIER) ? AiToolType.QUERY_SUPPLIER
                : requested.contains(AiToolType.QUERY_OPEN_PO) ? AiToolType.QUERY_OPEN_PO
                : AiToolType.SEARCH_DOCUMENTS;
        List<String> codes = materialCodes(normalized);
        return new AiQueryPlan(tool, recommendationId(normalized), codes.isEmpty() ? null : codes.getFirst(),
                normalized, codes, materialName(normalized), supplierCode(normalized), supplierName(normalized),
                purchaseOrderNumber(normalized));
    }

    /** Auxiliary fail-closed input validation; the model, not this regex, selects the tool. */
    public String validate(String message) {
        String normalized = message.trim();
        if (FORBIDDEN.matcher(normalized).find()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return normalized;
    }

    /** Only a missing identifier for an identified record is answered without contacting the model. */
    public String missingIdentifier(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (!materialCodes(message).isEmpty() || supplierCode(message) != null || purchaseOrderNumber(message) != null
                || recommendationId(message) != null) return null;
        if (containsAny(lower, "庫存", "存量", "還剩多少", "inventory", "stock") && materialName(message) == null) {
            return "請補充 Material Code（例如 MAT-001），並重新提出完整問題。";
        }
        if ((containsAny(lower, "單價", "平均價格", "平均報價", "報價", "price", "quote")
                || containsAny(lower, "供應商", "supplier", "可靠度", "品質評分", "評價"))
                && materialName(message) == null) {
            return "請補充物料代碼或物料名稱；查詢供應商評價時也可提供供應商代碼或名稱。";
        }
        if (containsAny(lower, "建議", "recommendation", "供應商排名", "supplier ranking")) {
            return "請補充 Material Code 或 Recommendation ID，並重新提出完整問題。";
        }
        if (containsAny(lower, "這張採購單", "這筆採購單", "這張 po", "this po")) {
            return "請補充可識別的 PO 資訊，並重新提出完整問題。";
        }
        return null;
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    public Long recommendationId(String message) {
        Matcher matcher = RECOMMENDATION_ID.matcher(message);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    /** Extracts only explicit code syntax; the selected read tool remains responsible for existence and access checks. */
    public String materialCode(String message) {
        List<String> codes = materialCodes(message);
        return codes.isEmpty() ? null : codes.getFirst();
    }

    public List<String> materialCodes(String message) {
        Matcher matcher = MATERIAL_CODE.matcher(message);
        List<String> codes = new ArrayList<>();
        while (matcher.find()) {
            String candidate = matcher.group(1).toUpperCase(Locale.ROOT);
            if (!candidate.startsWith("SUP-") && !candidate.startsWith("SUPPLIER-")
                    && !candidate.startsWith("PO-")) codes.add(candidate);
        }
        return codes.stream().distinct().toList();
    }

    public String supplierCode(String message) {
        Matcher matcher = SUPPLIER_CODE.matcher(message);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
    }

    public String purchaseOrderNumber(String message) {
        Matcher matcher = PURCHASE_ORDER_NUMBER.matcher(message);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
    }

    public String supplierName(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (supplierCode(message) != null || !containsAny(lower, "可靠度", "品質評分", "評價", "評分", "狀態", "啟用", "停用", "supplier rating", "supplier score")) {
            return null;
        }
        String candidate = message.replaceAll("(?i)目前|現在|我想確認一下|請問|供應商|可靠度|品質評分|"
                + "評價|評分|分數|狀態|是否|啟用|停用|的|為何|多少|如何|supplier|rating|score", " ")
                .replaceAll("[\\p{Punct}\\p{Zs}？?！!，,。；;：:、]+", " ").trim();
        if (candidate.isBlank() || candidate.matches("[A-Za-z0-9 _-]+")) return null;
        return candidate.length() >= 2 ? candidate : null;
    }

    /** Returns only literal question text left after removing common inventory/price query wording. */
    public String materialName(String message) {
        if (message == null || !containsAny(message.toLowerCase(Locale.ROOT), "庫存", "存量", "還剩多少", "inventory", "stock",
                "單價", "平均價格", "平均報價", "價格", "供應商", "supplier", "可靠度", "品質評分",
                "評價", "評分", "報價", "有哪些")) return null;
        String candidate = message.replaceAll("(?i)目前|現在|我想確認一下|我想查詢|請問|請用|告訴我|"
                + "庫存|存量|還剩多少|多少|單價|平均價格|平均報價|平均單價|價格|報價|供應商|"
                + "有哪些|可靠度|品質評分|評價|評分|狀態|啟用|停用|查詢|清單|這個|這筆|原料|物料|的|有效|目前有哪些|多少錢|各自|一公斤|公斤|千克|公克|公升|毫升|kg|\u00a0|inventory|stock|unit price|average price|quote|supplier|rating|quality|active|inactive", " ")
                .replaceAll("[\\p{Punct}\\p{Zs}？?！!，,。；;：:、]+", " ").trim();
        if (candidate.isBlank() || candidate.matches("[A-Za-z0-9 _-]+")) return null;
        return candidate.length() >= 2 ? candidate : null;
    }
}
