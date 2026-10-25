package com.supplypilot.document.semantic;

import java.util.List;
import java.util.regex.Pattern;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.SemanticReviewStatus;
import org.springframework.stereotype.Component;

/** Deterministic outbound policy; ADMIN review and content screening must both pass. */
@Component
public class OutboundDocumentPolicy {
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(?:api[_ -]?key|secret|password|access[_ -]?token|private[_ -]?key|credential)"
                    + "\\s*[:：=]\\s*\\S+|\\bbearer\\s+[A-Za-z0-9._~+/-]{12,}"
                    + "|(?:密碼|金鑰|憑證)\\s*[:：=]\\s*\\S+"
                    + "|\\bAIza[A-Za-z0-9_-]{20,}\\b|\\bsk-[A-Za-z0-9_-]{16,}\\b");
    private static final Pattern BANK = Pattern.compile(
            "(?i)\\b(?:bank account|account number|routing number|iban|swift)"
                    + "\\s*[:：#=]?\\s*[A-Z]{0,4}\\d[\\dA-Z -]{5,}"
                    + "|(?:銀行帳號|銀行帳戶|匯款帳號|收款帳號|帳號)\\s*[:：#=]?\\s*\\d[\\d -]{5,}");
    private static final Pattern COMMERCIAL = Pattern.compile(
            "(?i)(?:NT\\$|US\\$|[$€¥]|\\b(?:TWD|USD|NTD|EUR)\\b)\\s*\\d[\\d,]*(?:\\.\\d+)?"
                    + "|\\d[\\d,]*(?:\\.\\d+)?\\s*(?:元|圓|%)"
                    + "|(?:單價|成本|折扣|報價金額|unit price|cost price|discount|profit margin)"
                    + "\\s*[:：=]\\s*\\d");
    private static final Pattern PERSONAL = Pattern.compile(
            "(?i)\\b(?:ssn|social security|passport|date of birth|home address|personal address|customer name|employee name)"
                    + "\\s*[:：=]\\s*\\S+|(?:身分證|護照|生日|出生日期|住址|地址|姓名)"
                    + "\\s*(?:[:：=]|為|是)\\s*\\S+");
    private static final Pattern DOCUMENT_PERSONAL_CONTEXT = Pattern.compile(
            "(?i)\\b(?:customer name|employee name|home address|personal address|passport|ssn)\\b"
                    + "|姓名|住址|地址|護照|身分證");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern TAIWAN_ID = Pattern.compile("(?i)\\b[A-Z][12]\\d{8}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?886[- ]?)?0?9\\d{2}[- ]?\\d{3}[- ]?\\d{3}(?!\\d)");

    public Decision inspect(DocumentMetadata document, List<String> chunks, String sourceHash) {
        if (document.getSemanticReviewInvalidatedAt() != null
                || !sourceHash.equals(document.getSemanticApprovedSourceHash())
                || !document.getVersion().equals(document.getSemanticReviewVersion())
                || document.getSemanticApprovedAt() == null || document.getSemanticReviewStatus() == null
                || !hasValidReviewAudit(document)) {
            return new Decision("REQUIRES_REVIEW", "OUTBOUND_APPROVAL_REQUIRED", List.of());
        }
        if (document.getSemanticReviewStatus() == SemanticReviewStatus.SKIPPED) {
            return new Decision("SKIPPED", document.getSemanticReviewReason(), List.of());
        }
        if (document.getSemanticReviewStatus() == SemanticReviewStatus.REQUIRES_REVIEW) {
            return new Decision("REQUIRES_REVIEW", document.getSemanticReviewReason(), List.of());
        }
        return inspectContents(chunks);
    }

    private boolean hasValidReviewAudit(DocumentMetadata document) {
        if ("USER".equals(document.getSemanticReviewActorType())) {
            return document.getSemanticApprovedBy() != null
                    && document.getSemanticApprovedBy().toString().equals(document.getSemanticReviewActor())
                    && "ADMIN_OUTBOUND_REVIEW".equals(document.getSemanticReviewTrigger());
        }
        return document.isDemoBaseline()
                && document.getSemanticApprovedBy() == null
                && "SYSTEM".equals(document.getSemanticReviewActorType())
                && "TRUSTED_DEMO_CORPUS".equals(document.getSemanticReviewActor())
                && "TRUSTED_DEMO_CORPUS_AUTO_REVIEW".equals(document.getSemanticReviewTrigger())
                && document.getSemanticReviewStatus().name().equals(document.getSemanticReviewPolicyDecision());
    }

    public Decision inspectContents(List<String> chunks) {
        if (chunks.isEmpty() || chunks.stream().anyMatch(text -> text == null || text.isBlank())) {
            return new Decision("REQUIRES_REVIEW", "NO_SAFE_CHUNKS", List.of());
        }
        if (chunks.stream().anyMatch(text -> SECRET.matcher(text).find() || BANK.matcher(text).find()
                || COMMERCIAL.matcher(text).find() || PERSONAL.matcher(text).find()
                || LONG_NUMBER.matcher(text).find() || DOCUMENT_PERSONAL_CONTEXT.matcher(text).find())) {
            return new Decision("REQUIRES_REVIEW", "SENSITIVE_BUSINESS_OR_CREDENTIAL_DATA", List.of());
        }
        List<String> safe = chunks.stream().map(text -> redactAtSameOffsets(text, EMAIL, "[EMAIL]"))
                .map(text -> redactAtSameOffsets(text, TAIWAN_ID, "[ID]"))
                .map(text -> redactAtSameOffsets(text, PHONE, "[PHONE]"))
                .toList();
        return new Decision("APPROVED", null, safe);
    }

    private String redactAtSameOffsets(String text, Pattern pattern, String marker) {
        var matcher = pattern.matcher(text);
        StringBuilder sanitized = new StringBuilder(text.length());
        int copiedUntil = 0;
        while (matcher.find()) {
            sanitized.append(text, copiedUntil, matcher.start());
            int spanLength = matcher.end() - matcher.start();
            if (spanLength >= marker.length()) {
                sanitized.append(marker).append("*".repeat(spanLength - marker.length()));
            } else {
                sanitized.append("*".repeat(spanLength));
            }
            copiedUntil = matcher.end();
        }
        return sanitized.append(text, copiedUntil, text.length()).toString();
    }

    public String safeQuery(String question) {
        if (SECRET.matcher(question).find() || BANK.matcher(question).find()
                || COMMERCIAL.matcher(question).find() || PERSONAL.matcher(question).find()
                || LONG_NUMBER.matcher(question).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return PHONE.matcher(TAIWAN_ID.matcher(EMAIL.matcher(question)
                .replaceAll("[EMAIL]")).replaceAll("[ID]"))
                .replaceAll("[PHONE]");
    }

    public record Decision(String status, String reason, List<String> safeChunks) {
    }
}
