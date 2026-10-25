package com.supplypilot.document.semantic;

import java.util.List;
import java.util.regex.Pattern;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.demo.DemoCorpusTrustedApprovalService;
import com.supplypilot.document.domain.DocumentChunk;
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
                    + "\\s*[:：#=]?\\s*(?:[^\\r\\n\\d]{1,32}\\s*)?[A-Z]{0,4}\\d[\\dA-Z -]{5,}"
                    + "|\\b(?:swift|bic)(?:\\s+(?:code|identifier))?\\s*[:：#=]?\\s*[A-Z0-9]{8}(?:[A-Z0-9]{3})?\\b"
                    + "|(?:銀行帳號|銀行帳戶|匯款帳號|收款帳號|帳號)\\s*[:：#=]?\\s*"
                    + "(?:[^\\r\\n\\d]{1,24}\\s*)?\\d[\\d -]{5,}");
    private static final String CURRENCY_CODES = "TWD|USD|NTD|EUR|CNY|RMB|JPY|HKD|GBP|KRW|SGD|CAD|AUD";
    private static final String CURRENCY_NAMES = "人民幣|人民币|日圓|日元|港幣|港币|英鎊|英镑|韓元|韩元|新加坡幣|新加坡币|加幣|加币|澳幣|澳币";
    private static final String CURRENCY = "(?:\\b(?:" + CURRENCY_CODES + ")\\b|" + CURRENCY_NAMES + ")";
    private static final String COMMERCIAL_AMOUNT = "\\d[\\d,]*(?:\\.\\d+)?";
    private static final Pattern COMMERCIAL = Pattern.compile(
            "(?i)(?:NT\\$|US\\$|[$€¥]|" + CURRENCY + ")\\s*" + COMMERCIAL_AMOUNT
                    + "|" + COMMERCIAL_AMOUNT + "\\s*" + CURRENCY
                    + "|" + COMMERCIAL_AMOUNT + "\\s*(?:元|圓|%)"
                    + "|(?:單價|成本|折扣|報價金額|報價|價格|金額|預算|採購價|售價|unit price|cost price|discount|profit margin|price|amount)"
                    + "\\s*(?:(?:[:：=]\\s*)|\\s+)?\\d");
    private static final Pattern PERSONAL_FIELD_VALUE = Pattern.compile(
            "(?i)\\b(?:ssn|social security|passport(?: number| no\\.?)?|date of birth)"
                    + "\\s*[:：=]\\s*[A-Z0-9][A-Z0-9 ./-]{2,}"
                    + "|(?:(?:身分證(?:字號|號碼)?|護照(?:號碼)?)"
                    + "|(?:^|[\\r\\n])\\s*(?:生日|出生日期))"
                    + "\\s*[:：=]\\s*[A-Z0-9][A-Z0-9./-]{5,}");
    private static final Pattern PERSONAL_NAME_FIELD_VALUE = Pattern.compile(
            "(?iu)(?:\\b(?:customer name|employee name|contact name|contact person|person name|account holder(?: name)?|beneficiary(?: name)?|payee(?: name)?)\\b"
                    + "|(?:個人姓名|姓名|聯絡人(?:姓名)?|窗口姓名|戶名|收款人(?:姓名)?))\\s*(?:[:：=]|為|是)"
                    + "\\s*([^\\r\\n,，;；.!?。！？]*)");
    private static final Pattern PERSONAL_ADDRESS_FIELD_VALUE = Pattern.compile(
            "(?iu)(?:\\b(?:home address|personal address|business address|contact address|address)\\b"
                    + "|(?:居住地址|住址|營業地址|聯絡地址|公司地址|地址))\\s*(?:[:：=]|為|是)"
                    + "\\s*([^\\r\\n,，;；.!?。！？]*)");
    private static final Pattern SAFE_FIELD_PLACEHOLDER = Pattern.compile(
            "(?iu)(?:未提供(?:個人姓名|姓名|聯絡人|聯絡方式|地址|資料)?"
                    + "|未建立(?:個人姓名|姓名|聯絡人|聯絡方式|地址|資料)?"
                    + "|未記載(?:個人姓名|姓名|聯絡人|地址|資料)?"
                    + "|沒有提供(?:個人姓名|姓名|聯絡人|地址|資料)?"
                    + "|無(?:資料|資訊|個人姓名|地址)?|不適用|匿名|N/?A|none|not provided"
                    + "|not recorded|not applicable|anonymous"
                    + "|虛構\\s*(?:Demo\\s*)?(?:練習\\s*)?(?:窗口角色|採購窗口|聯絡窗口|業務窗口|案例角色|姓名|地址|練習地址|資料)"
                    + "|fictional\\s+(?:contact|business|address|name|role)(?:\\s+role)?)");
    private static final Pattern PASSPORT_NUMBER = Pattern.compile(
            "(?i)(?:護照(?:號碼)?|passport(?: number| no\\.?)?)\\s*[:：=]\\s*[A-Z0-9][A-Z0-9-]{5,11}\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?i)(?<![A-Z0-9-])(?:\\d[ -]?){13,19}(?![A-Z0-9-])");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern TAIWAN_ID = Pattern.compile("(?i)\\b[A-Z][12]\\d{8}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?886[- ]?)?(?:0?9\\d{2}[- ]?\\d{3}[- ]?\\d{3}"
                    + "|0?2[- ]?\\d{4}[- ]?\\d{4}|0?(?:3|4|5|6|7|8)\\d?[- ]?\\d{3,4}[- ]?\\d{4})(?!\\d)");

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
        return inspectContents(chunks, false);
    }

    /**
     * Allows commercial amounts only after the Demo approval service has verified the pinned
     * repository catalog, source bytes, document provenance and both Demo database targets.
     * Credential, PII, identity-number and bank checks remain identical to ordinary uploads.
     */
    public Decision inspectVerifiedDemoContents(DocumentMetadata document, List<DocumentChunk> sourceChunks,
            String fileHash, DemoCorpusTrustedApprovalService.VerifiedDemoSource evidence) {
        if (document == null || sourceChunks == null || evidence == null) {
            return new Decision("REQUIRES_REVIEW", "TRUSTED_DEMO_EVIDENCE_REQUIRED", List.of());
        }
        String chunkHash = SemanticBackfillService.sourceHash(sourceChunks);
        if (!evidence.matchesForApproval(document, fileHash, chunkHash)) {
            return new Decision("REQUIRES_REVIEW", "TRUSTED_DEMO_EVIDENCE_REQUIRED", List.of());
        }
        return inspectContents(sourceChunks.stream().map(DocumentChunk::getContent).toList(),
                evidence.allowsFictionalCommercialValues());
    }

    /** Applies the fictional-commercial-data exception after revalidating the durable Demo review proof. */
    public Decision inspectVerifiedDemo(DocumentMetadata document, List<DocumentChunk> sourceChunks,
            DemoCorpusTrustedApprovalService.VerifiedDemoSource evidence) {
        if (document == null || sourceChunks == null || evidence == null) {
            return new Decision("REQUIRES_REVIEW", "TRUSTED_DEMO_EVIDENCE_REQUIRED", List.of());
        }
        String chunkHash = SemanticBackfillService.sourceHash(sourceChunks);
        if (!evidence.matchesForRetrieval(document, chunkHash, document.getSemanticIndexGeneration())) {
            return new Decision("REQUIRES_REVIEW", "TRUSTED_DEMO_EVIDENCE_REQUIRED", List.of());
        }
        return inspectContents(sourceChunks.stream().map(DocumentChunk::getContent).toList(),
                evidence.allowsFictionalCommercialValues());
    }

    private Decision inspectContents(List<String> chunks, boolean allowVerifiedDemoCommercialValues) {
        if (chunks.isEmpty() || chunks.stream().anyMatch(text -> text == null || text.isBlank())) {
            return new Decision("REQUIRES_REVIEW", "NO_SAFE_CHUNKS", List.of());
        }
        if (chunks.stream().anyMatch(text -> hasSensitiveContent(text, allowVerifiedDemoCommercialValues))) {
            return new Decision("REQUIRES_REVIEW", "SENSITIVE_BUSINESS_OR_CREDENTIAL_DATA", List.of());
        }
        List<String> safe = chunks.stream().map(text -> redactAtSameOffsets(text, EMAIL, "[EMAIL]"))
                .map(text -> redactAtSameOffsets(text, TAIWAN_ID, "[ID]"))
                .map(text -> redactAtSameOffsets(text, PHONE, "[PHONE]"))
                .toList();
        return new Decision("APPROVED", null, safe);
    }

    private boolean hasSensitiveContent(String text, boolean allowVerifiedDemoCommercialValues) {
        return SECRET.matcher(text).find() || BANK.matcher(text).find()
                || (!allowVerifiedDemoCommercialValues && COMMERCIAL.matcher(text).find())
                || PERSONAL_FIELD_VALUE.matcher(text).find() || hasSensitiveFieldValue(text, PERSONAL_NAME_FIELD_VALUE)
                || hasSensitiveFieldValue(text, PERSONAL_ADDRESS_FIELD_VALUE) || PASSPORT_NUMBER.matcher(text).find()
                || LONG_NUMBER.matcher(text).find() || TAIWAN_ID.matcher(text).find();
    }

    private boolean hasSensitiveFieldValue(String text, Pattern fieldPattern) {
        var matcher = fieldPattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1).strip().replaceAll("^[「『\\\"']|[」』\\\"']$", "").strip();
            if (!value.isEmpty() && !SAFE_FIELD_PLACEHOLDER.matcher(value).matches()) return true;
        }
        return false;
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
        if (hasSensitiveContent(question, false)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return PHONE.matcher(TAIWAN_ID.matcher(EMAIL.matcher(question)
                .replaceAll("[EMAIL]")).replaceAll("[ID]"))
                .replaceAll("[PHONE]");
    }

    public record Decision(String status, String reason, List<String> safeChunks) {
    }
}
