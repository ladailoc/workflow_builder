CREATE TABLE command_executions (
    id UUID PRIMARY KEY,
    scope_type VARCHAR(128) NOT NULL,
    scope_id UUID NOT NULL,
    command_id UUID NOT NULL,
    command_type VARCHAR(128) NOT NULL,
    actor_id UUID NOT NULL,
    expected_version BIGINT,
    request_hash VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_json JSONB,
    error_json JSONB,
    result_metadata_json JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,

    CONSTRAINT uq_command_executions_scope_command
        UNIQUE (scope_type, scope_id, command_id),
    CONSTRAINT ck_command_executions_scope_type
        CHECK (scope_type ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_command_executions_command_type
        CHECK (command_type ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_command_executions_expected_version
        CHECK (expected_version IS NULL OR expected_version >= 0),
    CONSTRAINT ck_command_executions_request_hash
        CHECK (btrim(request_hash) <> ''),
    CONSTRAINT ck_command_executions_status
        CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_command_executions_result_state
        CHECK (
            (status = 'IN_PROGRESS' AND result_json IS NULL AND error_json IS NULL)
            OR (status = 'SUCCEEDED' AND result_json IS NOT NULL AND error_json IS NULL)
            OR (status = 'FAILED' AND result_json IS NULL AND error_json IS NOT NULL)
        ),
    CONSTRAINT ck_command_executions_result_metadata
        CHECK (jsonb_typeof(result_metadata_json) = 'object'),
    CONSTRAINT ck_command_executions_completion_state
        CHECK ((status = 'IN_PROGRESS') = (completed_at IS NULL)),
    CONSTRAINT ck_command_executions_timestamps
        CHECK (completed_at IS NULL OR completed_at >= created_at)
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    actor_id UUID,
    principal_id UUID,
    correlation_id UUID NOT NULL,
    command_id UUID,
    metadata_json JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_audit_events_aggregate_type
        CHECK (aggregate_type ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_audit_events_event_type
        CHECK (event_type ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_audit_events_metadata
        CHECK (jsonb_typeof(metadata_json) = 'object')
);

CREATE INDEX ix_command_executions_scope_created
    ON command_executions (scope_type, scope_id, created_at DESC);

CREATE INDEX ix_command_executions_status_created
    ON command_executions (status, created_at);

CREATE INDEX ix_audit_events_aggregate_timeline
    ON audit_events (aggregate_type, aggregate_id, occurred_at);

CREATE INDEX ix_audit_events_type_occurred
    ON audit_events (event_type, occurred_at);

CREATE INDEX ix_audit_events_correlation
    ON audit_events (correlation_id, occurred_at);

CREATE OR REPLACE FUNCTION guard_command_execution_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'CommandExecution history cannot be hard-deleted'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status IN ('SUCCEEDED', 'FAILED') THEN
        RAISE EXCEPTION 'Terminal CommandExecution is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.scope_type <> OLD.scope_type
        OR NEW.scope_id <> OLD.scope_id
        OR NEW.command_id <> OLD.command_id
        OR NEW.command_type <> OLD.command_type
        OR NEW.actor_id <> OLD.actor_id
        OR NEW.expected_version IS DISTINCT FROM OLD.expected_version
        OR NEW.request_hash <> OLD.request_hash
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'CommandExecution identity and request are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION guard_audit_event_append_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'AuditEvent is append-only'
        USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER trg_command_executions_preserve_history
BEFORE UPDATE OR DELETE ON command_executions
FOR EACH ROW EXECUTE FUNCTION guard_command_execution_history();

CREATE TRIGGER trg_audit_events_append_only
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION guard_audit_event_append_only();
