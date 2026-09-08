-- V20: Join synchronization schema
-- Tracks durable join state per (event_id, node_definition_id, join_scope_id)
-- Tracks each arrived inbound branch occurrence with exactly-once downstream routing guarantees

CREATE TABLE join_states (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
    node_definition_id UUID NOT NULL REFERENCES workflow_nodes(id) ON DELETE RESTRICT,
    join_scope_id UUID NOT NULL,
    join_policy VARCHAR(32) NOT NULL DEFAULT 'AND',
    required_count INTEGER NOT NULL CHECK (required_count >= 1),
    arrived_count INTEGER NOT NULL DEFAULT 0 CHECK (arrived_count >= 0),
    status VARCHAR(32) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING', 'COMPLETED', 'CANCELLED')),
    join_node_execution_id UUID REFERENCES node_executions(id) ON DELETE RESTRICT,
    routed_downstream BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_join_states_scope UNIQUE (event_id, node_definition_id, join_scope_id)
);

CREATE INDEX ix_join_states_lookup ON join_states (event_id, node_definition_id, join_scope_id);

CREATE TABLE join_arrived_branches (
    id UUID PRIMARY KEY,
    join_state_id UUID NOT NULL REFERENCES join_states(id) ON DELETE CASCADE,
    inbound_execution_id UUID NOT NULL REFERENCES node_executions(id) ON DELETE RESTRICT,
    inbound_edge_id UUID NOT NULL REFERENCES workflow_edges(id) ON DELETE RESTRICT,
    arrived_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_join_arrived_branches UNIQUE (join_state_id, inbound_execution_id)
);

CREATE INDEX ix_join_arrived_branches_state ON join_arrived_branches (join_state_id);

CREATE OR REPLACE FUNCTION guard_join_state_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'JoinState history cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status IN ('COMPLETED', 'CANCELLED') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'Terminal JoinState cannot be revived or modified'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.event_id <> OLD.event_id
        OR NEW.node_definition_id <> OLD.node_definition_id
        OR NEW.join_scope_id <> OLD.join_scope_id THEN
        RAISE EXCEPTION 'JoinState identity and scope are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_join_states_guard
BEFORE UPDATE OR DELETE ON join_states
FOR EACH ROW EXECUTE FUNCTION guard_join_state_history();

CREATE TRIGGER trg_join_arrived_branches_append_only
BEFORE UPDATE OR DELETE ON join_arrived_branches
FOR EACH ROW EXECUTE FUNCTION guard_append_only_task_history();
