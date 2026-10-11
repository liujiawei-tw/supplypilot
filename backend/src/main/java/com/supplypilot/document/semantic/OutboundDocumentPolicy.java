package com.supplypilot.document.semantic;

import java.util.List;
import java.util.regex.Pattern;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentType;
import org.springframework.stereotype.Component;

/** Conservative, deterministic outbound policy. A reviewer must reclassify rejected documents before export. */
@Component
public class OutboundDocumentPolicy {
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(api[_ -]?key|secret|password|bearer|access[_ -]?token|private[_ -]?key|credential)\\b|密碼|金鑰|憑證");
    private static final Pattern BANK = Pattern.compile(
            "(?i)\\b(bank account|account number|routing number|iban|swift)\\b|帳號|銀行|匯款|收款");
    private static final Pattern COMMERCIAL = Pattern.compile(
            "(?i)\\b(unit price|quotation|discount|profit margin|cost price)\\b|報價|折扣|毛利|單價|成本");
    private static final Pattern PERSONAL = Pattern.compile(
            "(?i)\\b(ssn|social security|passport|date of birth|home address|personal address|customer name|employee name)\\b|身分證|護照|生日|出生日期|住址|地址|姓名");
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern TAIWAN_ID = Pattern.compile("(?i)\\b[A-Z][12]\\d{8}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?886[- ]?)?0?9\\d{2}[- ]?\\d{3}[- ]?\\d{3}(?!\\d)");

    public Decision inspect(DocumentMetadata document, List<String> chunks, String sourceHash) {
        if (document.getDocumentType() == DocumentType.SUPPLIER_CONTRACT
                || document.getDocumentType() == DocumentType.QUOTATION) {
            return new Decision("SKIPPED", "DOCUMENT_TYPE_NOT_APPROVED", List.of());
        }
        if (!sourceHash.equals(document.getSemanticApprovedSourceHash())) {
            return new Decision("REQUIRES_REVIEW", "OUTBOUND_APPROVAL_REQUIRED", List.of());
        }
        if (chunks.isEmpty() || chunks.stream().anyMatch(String::isBlank)) {
            return new Decision("REQUIRES_REVIEW", "NO_SAFE_CHUNKS", List.of());
        }
        if (chunks.stream().anyMatch(text -> SECRET.matcher(text).find() || BANK.matcher(text).find()
                || COMMERCIAL.matcher(text).find() || PERSONAL.matcher(text).find()
                || LONG_NUMBER.matcher(text).find())) {
            return new Decision("REQUIRES_REVIEW", "SENSITIVE_BUSINESS_OR_CREDENTIAL_DATA", List.of());
        }
        List<String> safe = chunks.stream().map(text -> EMAIL.matcher(text).replaceAll("[EMAIL_REDACTED]"))
                .map(text -> TAIWAN_ID.matcher(text).replaceAll("[ID_REDACTED]"))
                .map(text -> PHONE.matcher(text).replaceAll("[PHONE_REDACTED]"))
                .toList();
        return new Decision("APPROVED", null, safe);
    }

    public String safeQuery(String question) {
        if (SECRET.matcher(question).find() || BANK.matcher(question).find()
                || COMMERCIAL.matcher(question).find() || PERSONAL.matcher(question).find()
                || LONG_NUMBER.matcher(question).find()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return PHONE.matcher(TAIWAN_ID.matcher(EMAIL.matcher(question)
                .replaceAll("[EMAIL_REDACTED]")).replaceAll("[ID_REDACTED]"))
                .replaceAll("[PHONE_REDACTED]");
    }

    public record Decision(String status, String reason, List<String> safeChunks) {
    }
}
