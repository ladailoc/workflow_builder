CREATE TABLE task_aggregation_states (
    node_execution_id UUID PRIMARY KEY REFERENCES node_executions(id) ON DELETE RESTRICT,
    total_tasks INTEGER NOT NULL,
    decision_policy VARCHAR(40),
    completion_policy VARCHAR(32),
    threshold INTEGER,
    reject_behavior VARCHAR(16) NOT NULL,
    remaining_task_behavior VARCHAR(32) NOT NULL,
    completed_tasks INTEGER NOT NULL DEFAULT 0,
    approvals INTEGER NOT NULL DEFAULT 0,
    rejections INTEGER NOT NULL DEFAULT 0,
    outcome VARCHAR(32),
    completion_claimed BOOLEAN NOT NULL DEFAULT FALSE,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_task_aggregation_total CHECK(total_tasks > 0),
    CONSTRAINT ck_task_aggregation_policy_kind CHECK(
      (decision_policy IS NOT NULL AND completion_policy IS NULL) OR
      (decision_policy IS NULL AND completion_policy IS NOT NULL)),
    CONSTRAINT ck_task_aggregation_threshold CHECK(threshold IS NULL OR threshold > 0)
);

CREATE TABLE task_aggregation_votes (
    task_id UUID PRIMARY KEY REFERENCES task_executions(id) ON DELETE RESTRICT,
    node_execution_id UUID NOT NULL REFERENCES task_aggregation_states(node_execution_id) ON DELETE RESTRICT,
    outcome VARCHAR(32) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_task_aggregation_votes_node ON task_aggregation_votes(node_execution_id);
