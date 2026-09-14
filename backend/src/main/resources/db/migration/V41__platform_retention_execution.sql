CREATE TABLE retention_legal_holds (
    id UUID PRIMARY KEY,
    category VARCHAR(32) NOT NULL,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id UUID NOT NULL,
    reason TEXT NOT NULL,
    held_by UUID NOT NULL,
    held_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_retention_legal_hold_target UNIQUE (category, aggregate_type, aggregate_id),
    CONSTRAINT ck_retention_legal_hold_category CHECK (category IN (
        'AUDIT', 'EVENT', 'TASK_SUBMISSION', 'INTEGRATION_PAYLOAD',
        'ATTACHMENT', 'NOTIFICATION'
    )),
    CONSTRAINT ck_retention_legal_hold_reason CHECK (btrim(reason) <> '')
);

CREATE TRIGGER trg_retention_legal_holds_append_only
    BEFORE UPDATE OR DELETE ON retention_legal_holds
    FOR EACH ROW EXECUTE FUNCTION guard_append_only_task_history();

CREATE UNIQUE INDEX uq_retention_actions_effect_once
    ON retention_actions (category, aggregate_type, aggregate_id, action)
    WHERE action <> 'RETAIN';

CREATE OR REPLACE FUNCTION guard_audit_event_append_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        AND current_setting('app.retention_redaction', true) = 'on'
        AND NEW.aggregate_type = OLD.aggregate_type
        AND NEW.aggregate_id = OLD.aggregate_id
        AND NEW.event_type = OLD.event_type
        AND NEW.actor_id IS NOT DISTINCT FROM OLD.actor_id
        AND NEW.principal_id IS NOT DISTINCT FROM OLD.principal_id
        AND NEW.correlation_id = OLD.correlation_id
        AND NEW.command_id IS NOT DISTINCT FROM OLD.command_id
        AND NEW.occurred_at = OLD.occurred_at
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'AuditEvent is append-only'
        USING ERRCODE = '55000';
END
$$;

CREATE OR REPLACE FUNCTION guard_append_only_task_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        AND current_setting('app.retention_redaction', true) = 'on'
        AND TG_TABLE_NAME = 'task_decisions'
    THEN
        IF NEW.id = OLD.id
            AND NEW.task_id = OLD.task_id
            AND NEW.command_id = OLD.command_id
            AND NEW.actor_id = OLD.actor_id
            AND NEW.principal_id IS NOT DISTINCT FROM OLD.principal_id
            AND NEW.outcome = OLD.outcome
            AND NEW.comment IS NOT DISTINCT FROM OLD.comment
            AND NEW.created_at = OLD.created_at
        THEN
            RETURN NEW;
        END IF;
    END IF;

    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME
        USING ERRCODE = '55000';
END
$$;

CREATE OR REPLACE FUNCTION guard_runtime_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Runtime history cannot be hard-deleted'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'UPDATE'
        AND current_setting('app.retention_redaction', true) = 'on'
        AND TG_TABLE_NAME = 'events'
    THEN
        IF NEW.id = OLD.id
            AND NEW.ticket_id = OLD.ticket_id
            AND NEW.workflow_version_id = OLD.workflow_version_id
            AND NEW.started_ticket_revision_id = OLD.started_ticket_revision_id
            AND NEW.event_type = OLD.event_type
            AND NEW.status = OLD.status
            AND NEW.outcome IS NOT DISTINCT FROM OLD.outcome
            AND NEW.wait_reason IS NOT DISTINCT FROM OLD.wait_reason
            AND NEW.root_event_id = OLD.root_event_id
            AND NEW.parent_event_id IS NOT DISTINCT FROM OLD.parent_event_id
            AND NEW.parent_node_execution_id IS NOT DISTINCT FROM OLD.parent_node_execution_id
            AND NEW.previous_event_id IS NOT DISTINCT FROM OLD.previous_event_id
            AND NEW.restarted_from_event_id IS NOT DISTINCT FROM OLD.restarted_from_event_id
            AND NEW.trigger_type = OLD.trigger_type
            AND NEW.trigger_correlation_key IS NOT DISTINCT FROM OLD.trigger_correlation_key
            AND NEW.started_by = OLD.started_by
            AND NEW.started_at = OLD.started_at
            AND NEW.ended_at IS NOT DISTINCT FROM OLD.ended_at
        THEN
            RETURN NEW;
        END IF;
    END IF;

    IF (
        TG_TABLE_NAME = 'events'
        AND OLD.status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TERMINATED')
    ) OR (
        TG_TABLE_NAME = 'node_executions'
        AND OLD.status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED')
    ) THEN
        RAISE EXCEPTION 'Terminal runtime history cannot be revived or mutated'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$$;
