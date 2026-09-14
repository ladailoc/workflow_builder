-- V36: Align IntegrationExecution status with spec (SUCCEEDED) and add external_request_id

ALTER TABLE integration_executions ADD COLUMN IF NOT EXISTS external_request_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_integration_executions_external_request_id
    ON integration_executions(external_request_id);

ALTER TABLE integration_executions DROP CONSTRAINT IF EXISTS ck_integration_execution_status;
ALTER TABLE integration_executions ADD CONSTRAINT ck_integration_execution_status
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'COMPLETED', 'FAILED', 'WAITING_CALLBACK', 'MANUAL_RECONCILIATION'));

UPDATE integration_executions SET status = 'SUCCEEDED' WHERE status = 'COMPLETED';
