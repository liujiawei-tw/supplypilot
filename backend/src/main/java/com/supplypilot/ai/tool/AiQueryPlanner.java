package com.supplypilot.ai.tool;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class AiQueryPlanner {
    private static final Pattern MATERIAL_CODE = Pattern.compile("(?i)\\b[A-Z]{2,}[A-Z0-9_]*-[A-Z0-9]+\\b");
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
            + "|^\\s*(?:請(?!問)(?:你)?|幫我|替我|立即|直接)(?:立即|直接)?(?:幫我|替我)?"
            + "(?:執行|操作|進行)?(?:收貨|核准|駁回|下單|調整庫存|建立(?:採購)?草稿)"
            + "|(?:幫我|替我)(?:立即|直接)?(?:執行|操作|進行)?"
            + "(?:收貨|核准|駁回|下單|調整庫存|建立(?:採購)?草稿))");

    public AiQueryPlan plan(String message) {
        String normalized = validate(message);
        String lower = normalized.toLowerCase(Locale.ROOT);
        AiToolType tool;
        if (containsAny(lower, "document", "contract", "quotation", "policy", "sop", "文件", "合約", "報價", "規範", "條款")) {
            tool = AiToolType.SEARCH_DOCUMENTS;
        } else if (containsAny(lower, "supplier", "ranking", "供應商", "排名")) {
            tool = AiToolType.QUERY_SUPPLIER_RANKING;
        } else if (containsAny(lower, "inventory", "stock", "庫存")) {
            tool = AiToolType.QUERY_INVENTORY;
        } else if (containsAny(lower, "purchase order", "open po", " po", "採購單", "未收貨", "在途")) {
            tool = AiToolType.QUERY_OPEN_PO;
        } else if (containsAny(lower, "recommendation", "recommended", "建議", "推薦")) {
            tool = containsAny(lower, "why", "explain", "為什麼", "原因")
                    ? AiToolType.EXPLAIN_RECOMMENDATION : AiToolType.QUERY_RECOMMENDATION;
        } else {
            tool = AiToolType.SEARCH_DOCUMENTS;
        }
        return new AiQueryPlan(tool, recommendationId(normalized), materialCode(normalized), normalized);
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
        if (materialCode(message) != null || recommendationId(message) != null) return null;
        if (containsAny(lower, "庫存", "inventory", "stock")) {
            return "請補充 Material Code（例如 MAT-001），並重新提出完整問題。";
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
        Matcher matcher = MATERIAL_CODE.matcher(message);
        while (matcher.find()) {
            String candidate = matcher.group().toUpperCase(Locale.ROOT);
            if (!candidate.startsWith("SUP-") && !candidate.startsWith("SUPPLIER-")) return candidate;
        }
        return null;
    }
}
