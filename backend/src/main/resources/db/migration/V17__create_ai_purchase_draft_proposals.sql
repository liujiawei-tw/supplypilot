CREATE TABLE ai_purchase_draft_proposals (
    id CHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    purchase_order_id BIGINT NULL,
    CONSTRAINT fk_ai_draft_proposal_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_ai_draft_proposal_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id),
    CONSTRAINT uk_ai_draft_proposal_po UNIQUE (purchase_order_id),
    INDEX idx_ai_draft_proposal_user_expiry (user_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
