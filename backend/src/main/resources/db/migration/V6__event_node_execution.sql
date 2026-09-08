CREATE TABLE events (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    workflow_version_id UUID NOT NULL,
    started_ticket_revision_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    outcome VARCHAR(128),
    wait_reason VARCHAR(64),
    root_event_id UUID NOT NULL,
    parent_event_id UUID,
    parent_node_execution_id UUID,
    previous_event_id UUID,
    restarted_from_event_id UUID,
    trigger_type VARCHAR(128) NOT NULL,
    trigger_correlation_key VARCHAR(512),
    variables_json JSONB NOT NULL DEFAULT '{}'::JSONB,
    started_by UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_events_id_ticket
        UNIQUE (id, ticket_id),
    CONSTRAINT fk_events_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES tickets (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_events_workflow_version
        FOREIGN KEY (workflow_version_id)
        REFERENCES workflow_versions (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_events_started_revision_same_ticket
        FOREIGN KEY (ticket_id, started_ticket_revision_id)
        REFERENCES ticket_revisions (ticket_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_events_root_same_ticket
        FOREIGN KEY (root_event_id, ticket_id)
        REFERENCES events (id, ticket_id)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_events_parent_same_ticket
        FOREIGN KEY (parent_event_id, ticket_id)
        REFERENCES events (id, ticket_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_events_previous_same_ticket
        FOREIGN KEY (previous_event_id, ticket_id)
        REFERENCES events (id, ticket_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_events_restarted_from_same_ticket
        FOREIGN KEY (restarted_from_event_id, ticket_id)
        REFERENCES events (id, ticket_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_events_type
        CHECK (event_type IN ('ROOT', 'CHILD')),
    CONSTRAINT ck_events_status
        CHECK (status IN (
            'CREATED', 'RUNNING', 'WAITING', 'COMPLETED',
            'FAILED', 'CANCELLED', 'TERMINATED'
        )),
    CONSTRAINT ck_events_outcome
        CHECK (outcome IS NULL OR outcome ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_events_wait_reason
        CHECK (
            wait_reason IS NULL
            OR wait_reason IN (
                'HUMAN_TASK', 'TIMER', 'EXTERNAL_CALLBACK',
                'CHILD_EVENT', 'JOIN', 'RETRY_BACKOFF'
            )
        ),
    CONSTRAINT ck_events_wait_state
        CHECK ((status = 'WAITING') = (wait_reason IS NOT NULL)),
    CONSTRAINT ck_events_hierarchy
        CHECK (
            (
                event_type = 'ROOT'
                AND root_event_id = id
                AND parent_event_id IS NULL
                AND parent_node_execution_id IS NULL
            )
            OR (
                event_type = 'CHILD'
                AND root_event_id <> id
                AND parent_event_id IS NOT NULL
                AND parent_node_execution_id IS NOT NULL
            )
        ),
    CONSTRAINT ck_events_history_not_self
        CHECK (
            (previous_event_id IS NULL OR previous_event_id <> id)
            AND (restarted_from_event_id IS NULL OR restarted_from_event_id <> id)
        ),
    CONSTRAINT ck_events_trigger_type
        CHECK (trigger_type ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_events_trigger_correlation
        CHECK (
            trigger_correlation_key IS NULL
            OR btrim(trigger_correlation_key) <> ''
        ),
    CONSTRAINT ck_events_variables_object
        CHECK (jsonb_typeof(variables_json) = 'object'),
    CONSTRAINT ck_events_timestamps
        CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT ck_events_terminal_timestamp
        CHECK (
            (status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TERMINATED'))
            = (ended_at IS NOT NULL)
        ),
    CONSTRAINT ck_events_lock_version
        CHECK (lock_version >= 0)
);

CREATE TABLE node_executions (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    node_definition_id UUID NOT NULL,
    activation_key VARCHAR(512) NOT NULL,
    cycle_id UUID NOT NULL,
    iteration INTEGER NOT NULL,
    path_token VARCHAR(256) NOT NULL,
    item_token VARCHAR(256),
    split_scope_id UUID,
    join_scope_id UUID,
    status VARCHAR(32) NOT NULL,
    wait_reason VARCHAR(64),
    outcome_port VARCHAR(128),
    input_json JSONB,
    output_json JSONB,
    error_json JSONB,
    started_ticket_revision_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_node_executions_activation_key
        UNIQUE (activation_key),
    CONSTRAINT uq_node_executions_id_event
        UNIQUE (id, event_id),
    CONSTRAINT fk_node_executions_event
        FOREIGN KEY (event_id)
        REFERENCES events (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_node_executions_definition
        FOREIGN KEY (node_definition_id)
        REFERENCES workflow_nodes (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_node_executions_started_revision
        FOREIGN KEY (started_ticket_revision_id)
        REFERENCES ticket_revisions (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_node_executions_activation_key
        CHECK (btrim(activation_key) <> ''),
    CONSTRAINT ck_node_executions_iteration
        CHECK (iteration >= 0),
    CONSTRAINT ck_node_executions_path_token
        CHECK (btrim(path_token) <> ''),
    CONSTRAINT ck_node_executions_item_token
        CHECK (item_token IS NULL OR btrim(item_token) <> ''),
    CONSTRAINT ck_node_executions_status
        CHECK (status IN (
            'CREATED', 'READY', 'RUNNING', 'WAITING',
            'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED'
        )),
    CONSTRAINT ck_node_executions_wait_reason
        CHECK (
            wait_reason IS NULL
            OR wait_reason IN (
                'HUMAN_TASK', 'TIMER', 'EXTERNAL_CALLBACK',
                'CHILD_EVENT', 'JOIN', 'RETRY_BACKOFF'
            )
        ),
    CONSTRAINT ck_node_executions_wait_state
        CHECK ((status = 'WAITING') = (wait_reason IS NOT NULL)),
    CONSTRAINT ck_node_executions_outcome_port
        CHECK (
            outcome_port IS NULL
            OR outcome_port ~ '^[A-Z][A-Z0-9._-]{0,127}$'
        ),
    CONSTRAINT ck_node_executions_input_object
        CHECK (input_json IS NULL OR jsonb_typeof(input_json) = 'object'),
    CONSTRAINT ck_node_executions_output_object
        CHECK (output_json IS NULL OR jsonb_typeof(output_json) = 'object'),
    CONSTRAINT ck_node_executions_error_object
        CHECK (error_json IS NULL OR jsonb_typeof(error_json) = 'object'),
    CONSTRAINT ck_node_executions_failure_error
        CHECK ((status = 'FAILED') = (error_json IS NOT NULL)),
    CONSTRAINT ck_node_executions_timestamps
        CHECK (
            (started_at IS NULL OR started_at >= created_at)
            AND (ended_at IS NULL OR ended_at >= created_at)
        ),
    CONSTRAINT ck_node_executions_terminal_timestamp
        CHECK (
            (status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED'))
            = (ended_at IS NOT NULL)
        ),
    CONSTRAINT ck_node_executions_lock_version
        CHECK (lock_version >= 0)
);

ALTER TABLE events
    ADD CONSTRAINT fk_events_parent_node_same_parent_event
    FOREIGN KEY (parent_node_execution_id, parent_event_id)
    REFERENCES node_executions (id, event_id)
    ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_ticket_active_root_event
    ON events (ticket_id)
    WHERE event_type = 'ROOT'
      AND status IN ('CREATED', 'RUNNING', 'WAITING');

CREATE INDEX ix_events_ticket
    ON events (ticket_id, started_at DESC);

CREATE INDEX ix_events_status_started
    ON events (status, started_at);

CREATE INDEX ix_events_workflow_version
    ON events (workflow_version_id, started_at DESC);

CREATE INDEX ix_events_trigger_correlation
    ON events (trigger_type, trigger_correlation_key)
    WHERE trigger_correlation_key IS NOT NULL;

CREATE INDEX ix_node_executions_event_status
    ON node_executions (event_id, status, created_at);

CREATE INDEX ix_node_executions_event_definition
    ON node_executions (event_id, node_definition_id, created_at);

CREATE OR REPLACE FUNCTION validate_event_runtime_ownership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    bound_version_status VARCHAR(32);
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT status
          INTO bound_version_status
          FROM workflow_versions
         WHERE id = NEW.workflow_version_id;

        IF bound_version_status IS DISTINCT FROM 'PUBLISHED' THEN
            RAISE EXCEPTION 'A new Event must bind an exact PUBLISHED WorkflowVersion'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.workflow_version_id <> OLD.workflow_version_id
        OR NEW.ticket_id <> OLD.ticket_id
        OR NEW.started_ticket_revision_id <> OLD.started_ticket_revision_id
        OR NEW.event_type <> OLD.event_type
        OR NEW.root_event_id <> OLD.root_event_id
        OR NEW.parent_event_id IS DISTINCT FROM OLD.parent_event_id
        OR NEW.parent_node_execution_id IS DISTINCT FROM OLD.parent_node_execution_id THEN
        RAISE EXCEPTION 'Event identity, version binding, and hierarchy are immutable'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION validate_node_execution_runtime_ownership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    event_version_id UUID;
    event_ticket_id UUID;
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.event_id <> OLD.event_id
        OR NEW.node_definition_id <> OLD.node_definition_id
        OR NEW.activation_key <> OLD.activation_key
        OR NEW.cycle_id <> OLD.cycle_id
        OR NEW.iteration <> OLD.iteration
        OR NEW.path_token <> OLD.path_token
        OR NEW.item_token IS DISTINCT FROM OLD.item_token
        OR NEW.split_scope_id IS DISTINCT FROM OLD.split_scope_id
        OR NEW.join_scope_id IS DISTINCT FROM OLD.join_scope_id
        OR NEW.started_ticket_revision_id <> OLD.started_ticket_revision_id
    ) THEN
        RAISE EXCEPTION 'NodeExecution occurrence identity is immutable'
            USING ERRCODE = '23514';
    END IF;

    SELECT workflow_version_id, ticket_id
      INTO event_version_id, event_ticket_id
      FROM events
     WHERE id = NEW.event_id;

    IF NOT EXISTS (
        SELECT 1
          FROM workflow_nodes
         WHERE id = NEW.node_definition_id
           AND workflow_version_id = event_version_id
    ) THEN
        RAISE EXCEPTION 'NodeExecution definition must belong to the Event WorkflowVersion'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM ticket_revisions
         WHERE id = NEW.started_ticket_revision_id
           AND ticket_id = event_ticket_id
    ) THEN
        RAISE EXCEPTION 'NodeExecution revision must belong to the Event Ticket'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
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

    IF (
        TG_TABLE_NAME = 'events'
        AND OLD.status IN ('COMPLETED', 'CANCELLED', 'TERMINATED')
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

CREATE TRIGGER trg_events_validate_runtime_ownership
BEFORE INSERT OR UPDATE ON events
FOR EACH ROW EXECUTE FUNCTION validate_event_runtime_ownership();

CREATE TRIGGER trg_node_executions_validate_runtime_ownership
BEFORE INSERT OR UPDATE ON node_executions
FOR EACH ROW EXECUTE FUNCTION validate_node_execution_runtime_ownership();

CREATE TRIGGER trg_events_preserve_runtime_history
BEFORE UPDATE OR DELETE ON events
FOR EACH ROW EXECUTE FUNCTION guard_runtime_history();

CREATE TRIGGER trg_node_executions_preserve_runtime_history
BEFORE UPDATE OR DELETE ON node_executions
FOR EACH ROW EXECUTE FUNCTION guard_runtime_history();
