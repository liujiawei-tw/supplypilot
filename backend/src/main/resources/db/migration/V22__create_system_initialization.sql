CREATE TABLE system_initialization (
    initialization_key VARCHAR(80) NOT NULL PRIMARY KEY,
    initialized_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
