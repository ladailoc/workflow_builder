-- V26: System Action integration executions and attempt tracking

CREATE TABLE integration_executions (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
    node_execution_id UUID NOT NULL REFERENCES node_executions(id) ON DELETE RESTRICT,
    connector_key VARCHAR(100) NOT NULL,
    action_key VARCHAR(100) NOT NULL,
    action_version INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    logical_action_identity VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    callback_correlation_id VARCHAR(255),
    sanitized_request_json JSONB,
    sanitized_response_json JSONB,
    error_category VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_integration_execution_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'WAITING_CALLBACK'))
);

CREATE INDEX idx_integration_executions_node_execution_id
    ON integration_executions(node_execution_id);

CREATE INDEX idx_integration_executions_event_id
    ON integration_executions(event_id);

CREATE INDEX idx_integration_executions_idempotency_key
    ON integration_executions(idempotency_key);

CREATE INDEX idx_integration_executions_callback_corr_id
    ON integration_executions(callback_correlation_id)
    WHERE callback_correlation_id IS NOT NULL;

CREATE TABLE integration_attempts (
    id UUID PRIMARY KEY,
    integration_execution_id UUID NOT NULL REFERENCES integration_executions(id) ON DELETE CASCADE,
    attempt_number INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    sanitized_request_json JSONB,
    sanitized_response_json JSONB,
    error_category VARCHAR(50),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_integration_attempt
        UNIQUE (integration_execution_id, attempt_number),
    CONSTRAINT ck_integration_attempt_status
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILURE'))
);

CREATE INDEX idx_integration_attempts_execution_id
    ON integration_attempts(integration_execution_id);
