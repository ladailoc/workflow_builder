-- V19: Add MULTI_INSTANCE to wait_reason check constraints on events and node_executions

ALTER TABLE events DROP CONSTRAINT IF EXISTS ck_events_wait_reason;
ALTER TABLE events ADD CONSTRAINT ck_events_wait_reason
    CHECK (
        wait_reason IS NULL
        OR wait_reason IN (
            'HUMAN_TASK', 'TIMER', 'EXTERNAL_CALLBACK',
            'CHILD_EVENT', 'JOIN', 'RETRY_BACKOFF', 'MULTI_INSTANCE'
        )
    );

ALTER TABLE node_executions DROP CONSTRAINT IF EXISTS ck_node_executions_wait_reason;
ALTER TABLE node_executions ADD CONSTRAINT ck_node_executions_wait_reason
    CHECK (
        wait_reason IS NULL
        OR wait_reason IN (
            'HUMAN_TASK', 'TIMER', 'EXTERNAL_CALLBACK',
            'CHILD_EVENT', 'JOIN', 'RETRY_BACKOFF', 'MULTI_INSTANCE'
        )
    );
