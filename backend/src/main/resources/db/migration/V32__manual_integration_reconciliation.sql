-- Preserve uncertain non-idempotent external outcomes for explicit operator reconciliation.
ALTER TABLE integration_executions DROP CONSTRAINT IF EXISTS ck_integration_execution_status;
ALTER TABLE integration_executions ADD CONSTRAINT ck_integration_execution_status
    CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'WAITING_CALLBACK', 'MANUAL_RECONCILIATION'));

ALTER TABLE events DROP CONSTRAINT IF EXISTS ck_events_wait_reason;
ALTER TABLE events ADD CONSTRAINT ck_events_wait_reason
    CHECK (
        wait_reason IS NULL
        OR wait_reason IN (
            'HUMAN_TASK', 'TIMER', 'EXTERNAL_CALLBACK', 'CHILD_EVENT', 'JOIN',
            'RETRY_BACKOFF', 'MULTI_INSTANCE', 'MANUAL_RECONCILIATION'
        )
    );

ALTER TABLE node_executions DROP CONSTRAINT IF EXISTS ck_node_executions_wait_reason;
ALTER TABLE node_executions ADD CONSTRAINT ck_node_executions_wait_reason
    CHECK (
        wait_reason IS NULL
        OR wait_reason IN (
            'HUMAN_TASK', 'TIMER', 'EXTERNAL_CALLBACK', 'CHILD_EVENT', 'JOIN',
            'RETRY_BACKOFF', 'MULTI_INSTANCE', 'MANUAL_RECONCILIATION'
        )
    );
