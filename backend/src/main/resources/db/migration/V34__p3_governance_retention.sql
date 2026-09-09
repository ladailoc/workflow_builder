CREATE TABLE retention_policies (
    id UUID PRIMARY KEY,
    category VARCHAR(32) NOT NULL,
    retention_days INTEGER NOT NULL,
    expiry_action VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_retention_policy_category UNIQUE (category),
    CONSTRAINT ck_retention_policy_category CHECK (category IN (
        'AUDIT', 'EVENT', 'TASK_SUBMISSION', 'INTEGRATION_PAYLOAD',
        'ATTACHMENT', 'NOTIFICATION'
    )),
    CONSTRAINT ck_retention_policy_days CHECK (retention_days > 0),
    CONSTRAINT ck_retention_policy_action CHECK (expiry_action IN (
        'MASK', 'ANONYMIZE', 'HARD_DELETE'
    )),
    CONSTRAINT ck_retention_policy_time CHECK (updated_at >= created_at),
    CONSTRAINT ck_retention_policy_lock CHECK (lock_version >= 0)
);

CREATE INDEX ix_retention_policies_enabled_category
    ON retention_policies (enabled, category);

-- Runtime history remains protected by its existing RESTRICT FKs and append-only triggers.
-- This table records immutable decisions; a worker performs only the approved action after
-- checking current references again in its own short transaction.
CREATE TABLE retention_actions (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL REFERENCES retention_policies(id) ON DELETE RESTRICT,
    category VARCHAR(32) NOT NULL,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    reason TEXT NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_retention_actions_category CHECK (category IN (
        'AUDIT', 'EVENT', 'TASK_SUBMISSION', 'INTEGRATION_PAYLOAD',
        'ATTACHMENT', 'NOTIFICATION'
    )),
    CONSTRAINT ck_retention_actions_action CHECK (action IN (
        'RETAIN', 'MASK', 'ANONYMIZE', 'HARD_DELETE'
    )),
    CONSTRAINT ck_retention_actions_reason CHECK (btrim(reason) <> '')
);

CREATE INDEX ix_retention_actions_decided
    ON retention_actions (decided_at, id);

CREATE TRIGGER trg_retention_actions_append_only
    BEFORE UPDATE OR DELETE ON retention_actions
    FOR EACH ROW EXECUTE FUNCTION guard_append_only_task_history();
