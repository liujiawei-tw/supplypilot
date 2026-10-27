package com.supplypilot.agentic.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Outbound protection dedicated to Agentic SQL. Commercial business facts are permitted by the
 * experiment policy; system credentials are excluded and personal/banking identifiers are masked.
 * This policy is deliberately independent from the legacy document RAG approval workflow.
 */
@Component
public class AgenticSqlOutputPolicy {
    private static final Pattern NEVER_SEND_COLUMN = Pattern.compile(
            "(?i).*(?:password|passwd|secret|credential|token|api[_-]?key|private[_-]?key|"
                    + "access[_-]?key(?:[_-]?id)?|signing[_-]?key|encryption[_-]?key|jwt|storage[_-]?path|jdbc[_-]?url|"
                    + "database[_-]?url|db[_-]?url|connection[_-]?string).*"
    );
    private static final Pattern SENSITIVE_IDENTIFIER_COLUMN = Pattern.compile(
            "(?i).*(?:bank[_-]?(?:account|number)|(?:bank[_-]?)?acct[_-]?(?:id|no|number)|"
                    + "account[_-]?(?:id|no|number)|routing[_-]?number|card[_-]?(?:id|no|number)|credit[_-]?card|"
                    + "iban|swift|bic|"
                    + "ssn|social[_-]?security|passport|national[_-]?id|identity[_-]?number|tax[_-]?(?:id|number)|taiwan[_-]?id|"
                    + "email|e[_-]?mail|phone|mobile|contact[_-]?(?:name|person|email|phone|address)|"
                    + "personal[_-]?(?:name|address)|home[_-]?address|username|display[_-]?name|full[_-]?name|"
                    + "first[_-]?name|last[_-]?name|(?:employee|approver|buyer|requester|recipient|owner|operator|"
                    + "person)[_-]?name|employee[_-]?id|user[_-]?id|created[_-]?by|uploaded[_-]?by|"
                    + "身分證|護照|銀行帳號|銀行帳戶|匯款帳號|收款帳號|"
                    + "聯絡人|聯絡方式|聯絡地址|聯絡電話|電子郵件|手機|地址).*"
    );
    private static final Pattern EMAIL = Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern TAIWAN_ID = Pattern.compile("(?i)\\b[A-Z][12]\\d{8}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?886[- ]?)?(?:0?9\\d{2}[- ]?\\d{3}[- ]?\\d{3}"
                    + "|0?2[- ]?\\d{4}[- ]?\\d{4}|0?(?:3|4|5|6|7|8)\\d?[- ]?\\d{3,4}[- ]?\\d{4})(?!\\d)"
    );
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<![A-Z0-9-])(?:\\d[ -]?){13,}(?![A-Z0-9-])");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?:[\\\"']?(?:api[_ -]?key|access[_ -]?key(?:[_ -]?id)?|secret|password|"
                    + "(?:access|refresh|id)[_ -]?token|token|private[_ -]?key|credential)[\\\"']?"
                    + "\\s*[:：=]\\s*[\\\"']?\\S+|\\bbearer\\s+[A-Za-z0-9._~+/-]{12,}"
                    + "|(?:密碼|金鑰|憑證)\\s*[:：=]\\s*\\S+|\\bAIza[A-Za-z0-9_-]{20,}\\b"
                    + "|\\bsk-[A-Za-z0-9_-]{16,}\\b|\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
                    + "glpat-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,})\\b)"
    );
    private static final Pattern BANK_VALUE = Pattern.compile(
            "(?i)(?:bank account|account number|account no\\.?|acct(?:ount)?[_ -]?(?:no\\.?|number)?|"
                    + "routing number|iban|銀行(?:帳號|帳戶)|(?:匯款|收款)?帳號|帳戶)"
                    + "\\s*[:：#=]?\\s*(?:[^\\r\\n\\d]{1,32}\\s*)?\\d[\\dA-Z -]{5,}"
    );
    private static final Pattern SWIFT_BIC = Pattern.compile(
            "(?i)(?:\\bSWIFT(?:\\s*/\\s*BIC)?\\b|\\bBIC\\b)\\s*[:：#=]?\\s*"
                    + "[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?\\b"
    );
    private static final Pattern PERSONAL_FIELD = Pattern.compile(
            "(?iu)(?:\\b(?:ssn|social security|passport(?: number| no\\.?)?|date of birth|home address|personal address|contact name|contact person|person name)\\b"
                    + "|(?:護照(?:號碼)?|生日|出生日期|個人姓名|姓名|聯絡人(?:姓名)?|窗口姓名|居住地址|住址|聯絡地址|地址))"
                    + "\\s*[:：=]\\s*[^\\r\\n,，;；.!?。！？]+"
    );

    private final ObjectMapper objectMapper;

    public AgenticSqlOutputPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String sanitizeQuestion(String question) {
        return maskText(question);
    }

    public boolean isNeverSendColumn(String name) {
        return isNeverSendColumnName(name);
    }

    /** Shared credential-field classifier for metadata discovery and SQL output filtering. */
    public static boolean isNeverSendColumnName(String name) {
        return name != null && NEVER_SEND_COLUMN.matcher(name).matches();
    }

    /** Shared field classifier for SQL projection lineage and final value masking. */
    public static boolean isSensitiveIdentifierColumn(String name) {
        return name != null && SENSITIVE_IDENTIFIER_COLUMN.matcher(name).matches();
    }

    public String maskColumnValue(String column, String value) {
        if (value == null) return null;
        if (isSensitiveIdentifierColumn(column)) {
            return "[REDACTED_IDENTIFIER]";
        }
        return maskText(value);
    }

    public SanitizedRows sanitizeRows(List<Map<String, Object>> rows, int maxColumns, int maxCharacters) {
        List<Map<String, Object>> safeRows = new ArrayList<>();
        boolean truncated = false;
        for (Map<String, Object> row : rows) {
            LinkedHashMap<String, Object> safe = new LinkedHashMap<>();
            int keptColumns = 0;
            for (var cell : row.entrySet()) {
                if (isNeverSendColumn(cell.getKey())) continue;
                if (keptColumns >= maxColumns) {
                    truncated = true;
                    break;
                }
                safe.put(cell.getKey(), safeValue(cell.getKey(), cell.getValue()));
                keptColumns++;
            }
            safeRows.add(Collections.unmodifiableMap(new LinkedHashMap<>(safe)));
            if (serializedLength(safeRows) > maxCharacters) {
                safeRows.removeLast();
                truncated = true;
                break;
            }
        }
        return new SanitizedRows(List.copyOf(safeRows), truncated, serializedLength(safeRows));
    }

    private Object safeValue(String column, Object value) {
        if (isSensitiveIdentifierColumn(column)) {
            return "[REDACTED_IDENTIFIER]";
        }
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof Number) return maskColumnValue(column, value.toString());
        if (value instanceof byte[]) return "[BINARY_OMITTED]";
        String text = value.toString();
        return maskColumnValue(column, text);
    }

    private String maskText(String value) {
        String masked = SECRET_ASSIGNMENT.matcher(value).replaceAll("[SYSTEM_SECRET_REDACTED]");
        masked = SWIFT_BIC.matcher(masked).replaceAll("[BANK_IDENTIFIER_REDACTED]");
        masked = BANK_VALUE.matcher(masked).replaceAll("[BANK_ACCOUNT_REDACTED]");
        masked = PERSONAL_FIELD.matcher(masked).replaceAll("[PERSONAL_DATA_REDACTED]");
        masked = EMAIL.matcher(masked).replaceAll("[EMAIL_REDACTED]");
        masked = TAIWAN_ID.matcher(masked).replaceAll("[ID_REDACTED]");
        masked = PHONE.matcher(masked).replaceAll("[PHONE_REDACTED]");
        return LONG_NUMBER.matcher(masked).replaceAll("[IDENTIFIER_REDACTED]");
    }

    private int serializedLength(List<Map<String, Object>> rows) {
        try {
            return objectMapper.writeValueAsString(rows).length();
        } catch (JsonProcessingException exception) {
            return Integer.MAX_VALUE;
        }
    }

    public record SanitizedRows(List<Map<String, Object>> rows, boolean truncated, int characterCount) {
        public SanitizedRows {
            rows = List.copyOf(rows);
        }
    }
}
