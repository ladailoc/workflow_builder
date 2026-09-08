CREATE TABLE participant_snapshots (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    node_execution_id UUID NOT NULL,
    item_execution_id UUID,
    resolver_type VARCHAR(128) NOT NULL,
    resolver_config_hash VARCHAR(256) NOT NULL,
    resolver_config_json JSONB NOT NULL,
    resolution_status VARCHAR(32) NOT NULL,
    subject_type VARCHAR(128),
    subject_ref_id UUID,
    resolved_user_id UUID,
    participant_role VARCHAR(128) NOT NULL,
    snapshot_json JSONB NOT NULL,
    resolved_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_participant_snapshots_id_node
        UNIQUE (id, node_execution_id),
    CONSTRAINT fk_participant_snapshots_node_event
        FOREIGN KEY (node_execution_id, event_id)
        REFERENCES node_executions (id, event_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_participant_snapshots_resolver_type
        CHECK (resolver_type ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_participant_snapshots_config_hash
        CHECK (btrim(resolver_config_hash) <> ''),
    CONSTRAINT ck_participant_snapshots_config_object
        CHECK (jsonb_typeof(resolver_config_json) = 'object'),
    CONSTRAINT ck_participant_snapshots_resolution_status
        CHECK (resolution_status IN ('RESOLVED', 'NO_MATCH', 'FAILED')),
    CONSTRAINT ck_participant_snapshots_resolved_user
        CHECK ((resolution_status = 'RESOLVED') = (resolved_user_id IS NOT NULL)),
    CONSTRAINT ck_participant_snapshots_subject_reference
        CHECK ((subject_type IS NULL) = (subject_ref_id IS NULL)),
    CONSTRAINT ck_participant_snapshots_subject_type
        CHECK (
            subject_type IS NULL
            OR subject_type ~ '^[A-Z][A-Z0-9._-]{0,127}$'
        ),
    CONSTRAINT ck_participant_snapshots_role
        CHECK (participant_role ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_participant_snapshots_snapshot_object
        CHECK (jsonb_typeof(snapshot_json) = 'object')
);

CREATE TABLE task_executions (
    id UUID PRIMARY KEY,
    node_execution_id UUID NOT NULL,
    item_execution_id UUID,
    status VARCHAR(32) NOT NULL,
    outcome VARCHAR(64),
    assignee_id UUID,
    title_snapshot TEXT NOT NULL,
    description_snapshot TEXT,
    form_schema_json JSONB,
    input_snapshot_json JSONB NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    due_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_task_executions_node
        FOREIGN KEY (node_execution_id)
        REFERENCES node_executions (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_task_executions_status
        CHECK (status IN (
            'READY', 'CLAIMED', 'IN_PROGRESS',
            'COMPLETED', 'CANCELLED', 'EXPIRED'
        )),
    CONSTRAINT ck_task_executions_outcome
        CHECK (outcome IS NULL OR outcome ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_task_executions_outcome_state
        CHECK (
            (status = 'COMPLETED' AND outcome IS NOT NULL)
            OR (status IN ('READY', 'CLAIMED', 'IN_PROGRESS') AND outcome IS NULL)
            OR status IN ('CANCELLED', 'EXPIRED')
        ),
    CONSTRAINT ck_task_executions_assignee_state
        CHECK (
            status NOT IN ('CLAIMED', 'IN_PROGRESS', 'COMPLETED')
            OR assignee_id IS NOT NULL
        ),
    CONSTRAINT ck_task_executions_title
        CHECK (btrim(title_snapshot) <> ''),
    CONSTRAINT ck_task_executions_description
        CHECK (description_snapshot IS NULL OR btrim(description_snapshot) <> ''),
    CONSTRAINT ck_task_executions_form_schema_object
        CHECK (form_schema_json IS NULL OR jsonb_typeof(form_schema_json) = 'object'),
    CONSTRAINT ck_task_executions_input_snapshot_object
        CHECK (jsonb_typeof(input_snapshot_json) = 'object'),
    CONSTRAINT ck_task_executions_priority
        CHECK (priority BETWEEN 0 AND 100),
    CONSTRAINT ck_task_executions_timestamps
        CHECK (
            (due_at IS NULL OR due_at >= created_at)
            AND (started_at IS NULL OR started_at >= created_at)
            AND (completed_at IS NULL OR completed_at >= created_at)
            AND (
                started_at IS NULL
                OR completed_at IS NULL
                OR completed_at >= started_at
            )
        ),
    CONSTRAINT ck_task_executions_started_state
        CHECK (status <> 'IN_PROGRESS' OR started_at IS NOT NULL),
    CONSTRAINT ck_task_executions_terminal_timestamp
        CHECK (
            (status IN ('COMPLETED', 'CANCELLED', 'EXPIRED'))
            = (completed_at IS NOT NULL)
        ),
    CONSTRAINT ck_task_executions_lock_version
        CHECK (lock_version >= 0)
);

CREATE TABLE task_candidates (
    task_id UUID NOT NULL,
    user_id UUID NOT NULL,
    source_type VARCHAR(128) NOT NULL,
    source_snapshot_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (task_id, user_id),
    CONSTRAINT fk_task_candidates_task
        FOREIGN KEY (task_id)
        REFERENCES task_executions (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_task_candidates_source_type
        CHECK (source_type ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_task_candidates_source_snapshot
        CHECK (jsonb_typeof(source_snapshot_json) = 'object')
);

CREATE TABLE task_assignment_history (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    from_user_id UUID,
    to_user_id UUID,
    actor_id UUID NOT NULL,
    reason TEXT,
    metadata_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_task_assignment_history_task
        FOREIGN KEY (task_id)
        REFERENCES task_executions (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_task_assignment_history_action
        CHECK (action_type IN ('ASSIGN', 'CLAIM', 'UNCLAIM', 'REASSIGN')),
    CONSTRAINT ck_task_assignment_history_shape
        CHECK (
            (action_type = 'ASSIGN' AND from_user_id IS NULL AND to_user_id IS NOT NULL)
            OR (action_type = 'CLAIM' AND to_user_id IS NOT NULL)
            OR (action_type = 'UNCLAIM' AND from_user_id IS NOT NULL AND to_user_id IS NULL)
            OR (
                action_type = 'REASSIGN'
                AND from_user_id IS NOT NULL
                AND to_user_id IS NOT NULL
                AND from_user_id <> to_user_id
                AND reason IS NOT NULL
                AND btrim(reason) <> ''
            )
        ),
    CONSTRAINT ck_task_assignment_history_reason
        CHECK (reason IS NULL OR btrim(reason) <> ''),
    CONSTRAINT ck_task_assignment_history_metadata
        CHECK (jsonb_typeof(metadata_json) = 'object')
);

CREATE TABLE task_decisions (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    command_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    principal_id UUID,
    outcome VARCHAR(64) NOT NULL,
    form_data_json JSONB NOT NULL,
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_task_decisions_task
        FOREIGN KEY (task_id)
        REFERENCES task_executions (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_task_decisions_task
        UNIQUE (task_id),
    CONSTRAINT uq_task_decisions_command
        UNIQUE (command_id),
    CONSTRAINT ck_task_decisions_outcome
        CHECK (outcome ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_task_decisions_form_data
        CHECK (jsonb_typeof(form_data_json) = 'object'),
    CONSTRAINT ck_task_decisions_comment
        CHECK (comment IS NULL OR btrim(comment) <> '')
);

CREATE INDEX ix_participant_snapshots_event_node
    ON participant_snapshots (event_id, node_execution_id, resolved_at);

CREATE INDEX ix_participant_snapshots_resolved_user
    ON participant_snapshots (resolved_user_id, resolved_at DESC)
    WHERE resolved_user_id IS NOT NULL;

CREATE INDEX ix_task_executions_assignee_status_due
    ON task_executions (assignee_id, status, due_at);

CREATE INDEX ix_task_executions_node
    ON task_executions (node_execution_id, created_at);

CREATE INDEX ix_task_candidates_user
    ON task_candidates (user_id, created_at);

CREATE INDEX ix_task_assignment_history_task
    ON task_assignment_history (task_id, created_at);

CREATE INDEX ix_task_decisions_task_created
    ON task_decisions (task_id, created_at);

CREATE OR REPLACE FUNCTION guard_append_only_task_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME
        USING ERRCODE = '55000';
END
$$;

CREATE OR REPLACE FUNCTION guard_task_execution_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'TaskExecution history cannot be hard-deleted'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status IN ('COMPLETED', 'CANCELLED', 'EXPIRED') THEN
        RAISE EXCEPTION 'Terminal TaskExecution cannot be revived or mutated'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.node_execution_id <> OLD.node_execution_id
        OR NEW.item_execution_id IS DISTINCT FROM OLD.item_execution_id
        OR NEW.title_snapshot <> OLD.title_snapshot
        OR NEW.description_snapshot IS DISTINCT FROM OLD.description_snapshot
        OR NEW.form_schema_json IS DISTINCT FROM OLD.form_schema_json
        OR NEW.input_snapshot_json IS DISTINCT FROM OLD.input_snapshot_json
        OR NEW.priority <> OLD.priority
        OR NEW.due_at IS DISTINCT FROM OLD.due_at
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'TaskExecution identity and activation snapshots are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_participant_snapshots_append_only
BEFORE UPDATE OR DELETE ON participant_snapshots
FOR EACH ROW EXECUTE FUNCTION guard_append_only_task_history();

CREATE TRIGGER trg_task_candidates_append_only
BEFORE UPDATE OR DELETE ON task_candidates
FOR EACH ROW EXECUTE FUNCTION guard_append_only_task_history();

CREATE TRIGGER trg_task_assignment_history_append_only
BEFORE UPDATE OR DELETE ON task_assignment_history
FOR EACH ROW EXECUTE FUNCTION guard_append_only_task_history();

CREATE TRIGGER trg_task_decisions_append_only
BEFORE UPDATE OR DELETE ON task_decisions
FOR EACH ROW EXECUTE FUNCTION guard_append_only_task_history();

CREATE TRIGGER trg_task_executions_preserve_history
BEFORE UPDATE OR DELETE ON task_executions
FOR EACH ROW EXECUTE FUNCTION guard_task_execution_history();
