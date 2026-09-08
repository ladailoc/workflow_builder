CREATE TABLE notification_dispatches (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
    node_execution_id UUID REFERENCES node_executions(id) ON DELETE RESTRICT,
    task_id UUID REFERENCES task_executions(id) ON DELETE RESTRICT,
    channel VARCHAR(64) NOT NULL,
    recipient_user_id UUID NOT NULL,
    recipient_snapshot_json JSONB NOT NULL,
    template_snapshot_json JSONB NOT NULL,
    payload_json JSONB NOT NULL,
    dedup_key VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('READY','SENDING','SENT','FAILED','DEAD','CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    allow_after_terminal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    last_error_json JSONB,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_notification_dispatches_dedup UNIQUE (dedup_key),
    CONSTRAINT ck_notification_recipient_snapshot CHECK (jsonb_typeof(recipient_snapshot_json)='object'),
    CONSTRAINT ck_notification_template_snapshot CHECK (jsonb_typeof(template_snapshot_json)='object'),
    CONSTRAINT ck_notification_payload CHECK (jsonb_typeof(payload_json)='object'),
    CONSTRAINT ck_notification_error CHECK (last_error_json IS NULL OR jsonb_typeof(last_error_json)='object'),
    CONSTRAINT ck_notification_sent_at CHECK ((status='SENT')=(sent_at IS NOT NULL))
);

CREATE INDEX idx_notification_event ON notification_dispatches(event_id, created_at);
CREATE INDEX idx_notification_status ON notification_dispatches(status, updated_at);
CREATE INDEX idx_notification_recipient ON notification_dispatches(recipient_user_id, created_at);
