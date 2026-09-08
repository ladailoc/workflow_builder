-- V18: Multi-Instance Node Support
-- node_item_executions: one row per collection item per node activation.
-- multi_instance_states: aggregate counter for an activated multi-instance node.

-- ─────────────────────────────────────────────────────────────────
-- 1. MULTI-INSTANCE STATES (aggregate per node execution)
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE multi_instance_states (
    id                      UUID PRIMARY KEY,
    event_id                UUID NOT NULL,
    node_execution_id       UUID NOT NULL UNIQUE,
    execution_mode          VARCHAR(32) NOT NULL,
    total_items             INT NOT NULL,
    completed_items         INT NOT NULL DEFAULT 0,
    failed_items            INT NOT NULL DEFAULT 0,
    cancelled_items         INT NOT NULL DEFAULT 0,
    completion_policy       VARCHAR(32) NOT NULL,
    completion_threshold    INT,
    remaining_item_policy   VARCHAR(32) NOT NULL DEFAULT 'CANCEL_REMAINING',
    status                  VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    routed_downstream       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    lock_version            BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_mis_event
        FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_mis_node_execution
        FOREIGN KEY (node_execution_id) REFERENCES node_executions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_mis_execution_mode
        CHECK (execution_mode IN ('PARALLEL', 'SEQUENTIAL')),
    CONSTRAINT ck_mis_completion_policy
        CHECK (completion_policy IN ('ALL', 'ANY', 'N_OF_M', 'PERCENTAGE')),
    CONSTRAINT ck_mis_remaining_item_policy
        CHECK (remaining_item_policy IN ('CANCEL_REMAINING', 'KEEP_RUNNING')),
    CONSTRAINT ck_mis_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_mis_total_items
        CHECK (total_items > 0),
    CONSTRAINT ck_mis_completed_items
        CHECK (completed_items >= 0 AND completed_items <= total_items),
    CONSTRAINT ck_mis_failed_items
        CHECK (failed_items >= 0),
    CONSTRAINT ck_mis_threshold
        CHECK (completion_threshold IS NULL OR completion_threshold > 0),
    CONSTRAINT ck_mis_lock_version
        CHECK (lock_version >= 0)
);

CREATE INDEX ix_mis_event ON multi_instance_states (event_id);
CREATE INDEX ix_mis_status ON multi_instance_states (status);

-- ─────────────────────────────────────────────────────────────────
-- 2. NODE ITEM EXECUTIONS (one per item per multi-instance node)
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE node_item_executions (
    id                      UUID PRIMARY KEY,
    multi_instance_state_id UUID NOT NULL,
    event_id                UUID NOT NULL,
    parent_node_execution_id UUID NOT NULL,
    item_index              INT NOT NULL,
    item_token              VARCHAR(256) NOT NULL,
    item_data_json          JSONB,
    status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    outcome_port            VARCHAR(128),
    output_json             JSONB,
    error_json              JSONB,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    lock_version            BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_nie_mis
        FOREIGN KEY (multi_instance_state_id)
        REFERENCES multi_instance_states (id) ON DELETE RESTRICT,
    CONSTRAINT fk_nie_event
        FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_nie_parent_ne
        FOREIGN KEY (parent_node_execution_id)
        REFERENCES node_executions (id) ON DELETE RESTRICT,
    CONSTRAINT uq_nie_parent_item
        UNIQUE (parent_node_execution_id, item_index),
    CONSTRAINT ck_nie_status
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT ck_nie_item_index
        CHECK (item_index >= 0),
    CONSTRAINT ck_nie_lock_version
        CHECK (lock_version >= 0)
);

CREATE INDEX ix_nie_mis_id  ON node_item_executions (multi_instance_state_id, item_index);
CREATE INDEX ix_nie_event   ON node_item_executions (event_id);
CREATE INDEX ix_nie_parent  ON node_item_executions (parent_node_execution_id, status);

-- Guard: terminal item executions cannot be revived
CREATE OR REPLACE FUNCTION guard_nie_terminal()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED') THEN
        RAISE EXCEPTION 'Terminal NodeItemExecution cannot be mutated: id=%', OLD.id
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_nie_guard_terminal
BEFORE UPDATE ON node_item_executions
FOR EACH ROW EXECUTE FUNCTION guard_nie_terminal();
