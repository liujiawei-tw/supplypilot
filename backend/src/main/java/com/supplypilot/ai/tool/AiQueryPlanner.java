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
    private static final Pattern MATERIAL_CODE = Pattern.compile("(?i)\\b[A-Z]{2,}[A-Z0-9_]*-\\d+\\b");
    private static final Pattern RECOMMENDATION_ID = Pattern.compile(
            "(?i)(?:recommendation\\s*(?:id|#)|建議(?:編號| ID))\\s*[:#]?\\s*(\\d+)");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)(approve|reject|order\\s*po|receive\\s*po|adjust\\s*inventory|user\\s*management|"
                    + "master\\s*data\\s*write|create\\s*(?:purchase\\s*)?draft|核准|駁回|下單|(?<!未)收貨|"
                    + "調整庫存|使用者管理|主檔寫入|建立(?:採購)?草稿)");

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

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    private Long recommendationId(String message) {
        Matcher matcher = RECOMMENDATION_ID.matcher(message);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    private String materialCode(String message) {
        Matcher matcher = MATERIAL_CODE.matcher(message);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
    }
}
