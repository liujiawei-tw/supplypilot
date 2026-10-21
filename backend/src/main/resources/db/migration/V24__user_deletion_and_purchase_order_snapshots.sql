-- Preserve transactional rows while allowing their former user to be deleted.
-- MySQL DDL autocommits; apply with the application's normal Flyway backup/recovery
-- process. Every change is additive except nullable historical FK columns.

ALTER TABLE purchase_orders
    ADD COLUMN assigned_to BIGINT NULL,
    ADD COLUMN created_by_user_id_snapshot BIGINT NULL,
    ADD COLUMN created_by_username_snapshot VARCHAR(50) NULL,
    ADD COLUMN created_by_display_name_snapshot VARCHAR(100) NULL,
    ADD COLUMN approved_by_user_id_snapshot BIGINT NULL,
    ADD COLUMN approved_by_username_snapshot VARCHAR(50) NULL,
    ADD COLUMN approved_by_display_name_snapshot VARCHAR(100) NULL,
    ADD COLUMN rejected_by_user_id_snapshot BIGINT NULL,
    ADD COLUMN rejected_by_username_snapshot VARCHAR(50) NULL,
    ADD COLUMN rejected_by_display_name_snapshot VARCHAR(100) NULL,
    ADD COLUMN ordered_by_user_id_snapshot BIGINT NULL,
    ADD COLUMN ordered_by_username_snapshot VARCHAR(50) NULL,
    ADD COLUMN ordered_by_display_name_snapshot VARCHAR(100) NULL,
    ADD COLUMN received_by_user_id_snapshot BIGINT NULL,
    ADD COLUMN received_by_username_snapshot VARCHAR(50) NULL,
    ADD COLUMN received_by_display_name_snapshot VARCHAR(100) NULL;

UPDATE purchase_orders po
LEFT JOIN users creator ON creator.id = po.created_by
LEFT JOIN users approver ON approver.id = po.approved_by
LEFT JOIN users rejector ON rejector.id = po.rejected_by
LEFT JOIN users orderer ON orderer.id = po.ordered_by
LEFT JOIN users receiver ON receiver.id = po.received_by
SET po.assigned_to = po.created_by,
    po.created_by_user_id_snapshot = po.created_by,
    po.created_by_username_snapshot = creator.username,
    po.created_by_display_name_snapshot = creator.display_name,
    po.approved_by_user_id_snapshot = po.approved_by,
    po.approved_by_username_snapshot = approver.username,
    po.approved_by_display_name_snapshot = approver.display_name,
    po.rejected_by_user_id_snapshot = po.rejected_by,
    po.rejected_by_username_snapshot = rejector.username,
    po.rejected_by_display_name_snapshot = rejector.display_name,
    po.ordered_by_user_id_snapshot = po.ordered_by,
    po.ordered_by_username_snapshot = orderer.username,
    po.ordered_by_display_name_snapshot = orderer.display_name,
    po.received_by_user_id_snapshot = po.received_by,
    po.received_by_username_snapshot = receiver.username,
    po.received_by_display_name_snapshot = receiver.display_name;

ALTER TABLE purchase_orders
    DROP FOREIGN KEY fk_purchase_orders_created_by,
    DROP FOREIGN KEY fk_purchase_orders_approved_by,
    DROP FOREIGN KEY fk_purchase_orders_rejected_by,
    DROP FOREIGN KEY fk_purchase_orders_ordered_by,
    DROP FOREIGN KEY fk_purchase_orders_received_by,
    MODIFY COLUMN created_by BIGINT NULL;

ALTER TABLE purchase_orders
    ADD CONSTRAINT fk_purchase_orders_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_purchase_orders_approved_by FOREIGN KEY (approved_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_purchase_orders_rejected_by FOREIGN KEY (rejected_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_purchase_orders_ordered_by FOREIGN KEY (ordered_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_purchase_orders_received_by FOREIGN KEY (received_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_purchase_orders_assigned_to FOREIGN KEY (assigned_to) REFERENCES users(id) ON DELETE SET NULL,
    ADD INDEX idx_purchase_orders_assigned_to (assigned_to);

-- Keep non-PO historical records intact. Their former user references become nullable;
-- their current IDs are copied to non-FK snapshot columns before the FK is detached.
ALTER TABLE demands ADD COLUMN created_by_user_id_snapshot BIGINT NULL;
UPDATE demands SET created_by_user_id_snapshot = created_by;
ALTER TABLE demands DROP FOREIGN KEY fk_demands_created_by, MODIFY COLUMN created_by BIGINT NULL;
ALTER TABLE demands
    ADD CONSTRAINT fk_demands_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE stock_transactions ADD COLUMN created_by_user_id_snapshot BIGINT NULL;
UPDATE stock_transactions SET created_by_user_id_snapshot = created_by;
ALTER TABLE stock_transactions DROP FOREIGN KEY fk_stock_transactions_created_by, MODIFY COLUMN created_by BIGINT NULL;
ALTER TABLE stock_transactions
    ADD CONSTRAINT fk_stock_transactions_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE inventory_import_batches ADD COLUMN uploaded_by_user_id_snapshot BIGINT NULL;
UPDATE inventory_import_batches SET uploaded_by_user_id_snapshot = uploaded_by;
ALTER TABLE inventory_import_batches DROP FOREIGN KEY fk_inventory_import_batches_uploaded_by,
    MODIFY COLUMN uploaded_by BIGINT NULL;
ALTER TABLE inventory_import_batches
    ADD CONSTRAINT fk_inventory_import_batches_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE purchase_recommendation ADD COLUMN created_by_user_id_snapshot BIGINT NULL;
UPDATE purchase_recommendation SET created_by_user_id_snapshot = created_by;
ALTER TABLE purchase_recommendation DROP FOREIGN KEY fk_purchase_recommendation_created_by,
    MODIFY COLUMN created_by BIGINT NULL;
ALTER TABLE purchase_recommendation
    ADD CONSTRAINT fk_purchase_recommendation_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE document_metadata ADD COLUMN uploaded_by_user_id_snapshot BIGINT NULL;
UPDATE document_metadata SET uploaded_by_user_id_snapshot = uploaded_by;
ALTER TABLE document_metadata DROP FOREIGN KEY fk_document_metadata_uploaded_by,
    MODIFY COLUMN uploaded_by BIGINT NULL;
ALTER TABLE document_metadata
    ADD CONSTRAINT fk_document_metadata_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE ai_purchase_draft_proposals ADD COLUMN user_id_snapshot BIGINT NULL;
UPDATE ai_purchase_draft_proposals SET user_id_snapshot = user_id;
ALTER TABLE ai_purchase_draft_proposals DROP FOREIGN KEY fk_ai_draft_proposal_user,
    MODIFY COLUMN user_id BIGINT NULL;
ALTER TABLE ai_purchase_draft_proposals
    ADD CONSTRAINT fk_ai_draft_proposal_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;
