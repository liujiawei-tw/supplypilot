ALTER TABLE users
    ADD COLUMN can_manage_users BOOLEAN NOT NULL DEFAULT FALSE;

-- Only accounts that already held V1 ADMIN authority retain user-management access.
UPDATE users
SET can_manage_users = CASE WHEN role = 'ADMIN' THEN TRUE ELSE FALSE END;

-- V2 uses ADMIN for business operations; authorization to manage people is now separate.
UPDATE users SET role = 'ADMIN';
