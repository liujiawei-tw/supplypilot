CREATE TABLE demo_dataset_state (
    dataset_key VARCHAR(50) PRIMARY KEY,
    dataset_version VARCHAR(50) NOT NULL,
    baseline_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL,
    failure_reason VARCHAR(120) NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    CONSTRAINT chk_demo_dataset_state_status CHECK (
        status IN ('INITIALIZING', 'RELATIONAL_READY', 'COMPLETE', 'FAILED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
