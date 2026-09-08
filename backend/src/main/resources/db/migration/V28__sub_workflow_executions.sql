-- V28: Sub-workflow execution tracking and parent-child correlation

CREATE TABLE sub_workflow_executions (
    id UUID PRIMARY KEY,
    parent_event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    parent_node_execution_id UUID NOT NULL REFERENCES node_executions(id) ON DELETE CASCADE,
    child_event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    child_workflow_definition_id UUID NOT NULL REFERENCES workflow_definitions(id) ON DELETE RESTRICT,
    child_workflow_version_id UUID NOT NULL REFERENCES workflow_versions(id) ON DELETE RESTRICT,
    execution_mode VARCHAR(50) NOT NULL,
    cancellation_policy VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    input_snapshot_json JSONB,
    output_snapshot_json JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_sub_workflow_execution_mode
        CHECK (execution_mode IN ('WAIT_FOR_COMPLETION', 'FIRE_AND_CONTINUE')),
    CONSTRAINT ck_sub_workflow_cancellation_policy
        CHECK (cancellation_policy IN ('PROPAGATE', 'DETACH')),
    CONSTRAINT ck_sub_workflow_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT uq_sub_workflow_parent_node
        UNIQUE (parent_node_execution_id)
);

CREATE INDEX idx_sub_workflow_child_event
    ON sub_workflow_executions(child_event_id);

CREATE INDEX idx_sub_workflow_parent_event
    ON sub_workflow_executions(parent_event_id);
