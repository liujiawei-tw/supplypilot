CREATE TABLE ai_monthly_token_usage (
    period CHAR(7) PRIMARY KEY,
    known_tokens BIGINT NOT NULL DEFAULT 0,
    missing_usage_requests BIGINT NOT NULL DEFAULT 0,
    warning_issued BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_ai_monthly_known_tokens_nonnegative CHECK (known_tokens >= 0),
    CONSTRAINT ck_ai_monthly_missing_usage_nonnegative CHECK (missing_usage_requests >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
