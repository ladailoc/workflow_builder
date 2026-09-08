-- V27: Asynchronous integration callbacks and correlation tracking

CREATE TABLE integration_callbacks (
    id UUID PRIMARY KEY,
    callback_correlation_id VARCHAR(255) NOT NULL,
    integration_execution_id UUID REFERENCES integration_executions(id) ON DELETE SET NULL,
    connector_key VARCHAR(100) NOT NULL,
    external_event_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    signature VARCHAR(512),
    signature_valid BOOLEAN NOT NULL DEFAULT FALSE,
    callback_timestamp TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    sanitized_payload_json JSONB,
    error_message TEXT,
    CONSTRAINT ck_integration_callback_status
        CHECK (status IN ('ACCEPTED', 'DUPLICATE', 'LATE', 'REJECTED')),
    CONSTRAINT uq_integration_callbacks_connector_event
        UNIQUE (connector_key, external_event_id)
);

CREATE INDEX idx_integration_callbacks_correlation_id
    ON integration_callbacks(callback_correlation_id);

CREATE INDEX idx_integration_callbacks_execution_id
    ON integration_callbacks(integration_execution_id);