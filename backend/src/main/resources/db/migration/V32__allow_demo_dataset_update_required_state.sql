ALTER TABLE demo_dataset_state DROP CHECK chk_demo_dataset_state_status;

ALTER TABLE demo_dataset_state
    ADD CONSTRAINT chk_demo_dataset_state_status CHECK (
        status IN ('INITIALIZING', 'RELATIONAL_READY', 'COMPLETE', 'FAILED', 'UPDATE_REQUIRED')
    );
