package com.supplypilot.ai.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.document.rag.EvidenceValidator;
import org.springframework.stereotype.Component;

/** Checks whether a trusted tool result addresses the question, beyond merely having run successfully. */
@Component
public class AiAnswerEvidencePolicy {
    private static final Pattern DOCUMENT_TERM = Pattern.compile("(?i)(?:合約|契約|條款|文件|規範|採購\\s*SOP|"
            + "標準交期|交貨期|報價有效|有效期限|基本單位|儲存條件|儲存方式|隔離(?:批次|條件)|保險費率|"
            + "contract|quotation\\s+validity|standard\\s+lead\\s+time|standard\\s+delivery|"
            + "storage\\s+conditions?|quarantine\\s+conditions?|insurance\\s+(?:rate|premium)|"
            + "(?:purchase|procurement)\\s+sop)");
    private static final Pattern RECOMMENDATION_TERM = Pattern.compile("(?i)(?:採購建議|推薦|recommendation|recommended)");
    private static final Pattern RANKING_TERM = Pattern.compile("(?i)(?:供應商排名|供應商排序|supplier\\s+ranking|supplier\\s+rank)");
    private static final Pattern INVENTORY_TERM = Pattern.compile("(?i)(?:庫存|存量|inventory|stock\\s+(?:level|quantity))");
    private static final Pattern OPEN_PO_TERM = Pattern.compile("(?i)(?:未收貨(?:採購單)?|未完成採購單|在途(?:採購單)?|open\\s+(?:po|purchase\\s+orders?))");

    private final EvidenceValidator evidenceValidator;

    public AiAnswerEvidencePolicy(EvidenceValidator evidenceValidator) {
        this.evidenceValidator = evidenceValidator;
    }

    public List<AiToolResult> supportingResults(String question, List<AiToolResult> executions) {
        Set<AiToolType> requested = requestedTools(question);
        if (requested.isEmpty()) {
            return executions.stream().filter(this::answered).toList();
        }
        List<AiToolResult> supporting = executions.stream()
                .filter(this::answered)
                .filter(result -> requested.stream().anyMatch(tool -> satisfies(tool, result.tool())))
                .filter(result -> result.tool() != AiToolType.SEARCH_DOCUMENTS || supportedDocument(question, result))
                .toList();
        // A compound request is not complete when only one of its explicitly requested facts is present.
        if (requested.stream().anyMatch(tool -> supporting.stream().noneMatch(result -> satisfies(tool, result.tool())))) {
            return List.of();
        }
        return supporting;
    }

    private boolean answered(AiToolResult result) {
        return result != null && result.status() == AiQueryStatus.ANSWERED
                && result.trustedFacts() != null && !result.trustedFacts().isBlank()
                && result.sources() != null;
    }

    private boolean satisfies(AiToolType requested, AiToolType executed) {
        return requested == executed || requested == AiToolType.QUERY_RECOMMENDATION
                && executed == AiToolType.EXPLAIN_RECOMMENDATION;
    }

    private Set<AiToolType> requestedTools(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        java.util.EnumSet<AiToolType> requested = java.util.EnumSet.noneOf(AiToolType.class);
        if (DOCUMENT_TERM.matcher(normalized).find()) requested.add(AiToolType.SEARCH_DOCUMENTS);
        if (RECOMMENDATION_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_RECOMMENDATION);
        if (RANKING_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_SUPPLIER_RANKING);
        if (INVENTORY_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_INVENTORY);
        if (OPEN_PO_TERM.matcher(normalized).find()) requested.add(AiToolType.QUERY_OPEN_PO);
        return requested;
    }

    private boolean supportedDocument(String question, AiToolResult result) {
        return !result.sources().isEmpty() && result.sources().stream()
                .allMatch(source -> validDocumentSource(question, source));
    }

    private boolean validDocumentSource(String question, AiSourceResponse source) {
        return source != null && "DOCUMENT".equals(source.resourceType())
                && source.resourceId() != null && !source.resourceId().isBlank()
                && source.version() != null && !source.version().isBlank()
                && source.chunkIndex() != null && source.chunkIndex() >= 0
                && source.startOffset() != null && source.startOffset() >= 0
                && source.endOffset() != null && source.endOffset() > source.startOffset()
                && source.excerpt() != null
                && source.excerpt().length() == source.endOffset() - source.startOffset()
                && !evidenceValidator.supportedExcerpt(question, source.excerpt()).isBlank();
    }
}
