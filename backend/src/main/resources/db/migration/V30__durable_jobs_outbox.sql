CREATE TABLE workflow_jobs (
    id UUID PRIMARY KEY,
    job_type VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL CHECK (status IN ('READY', 'RUNNING', 'RETRY', 'COMPLETED', 'DEAD', 'CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    next_run_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(255),
    lease_until TIMESTAMPTZ,
    dedup_key VARCHAR(512) NOT NULL,
    last_error_json JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_workflow_jobs_dedup UNIQUE (dedup_key),
    CONSTRAINT ck_workflow_jobs_payload_object CHECK (jsonb_typeof(payload_json) = 'object'),
    CONSTRAINT ck_workflow_jobs_error_object CHECK (last_error_json IS NULL OR jsonb_typeof(last_error_json) = 'object'),
    CONSTRAINT ck_workflow_jobs_lease_pair CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_workflow_jobs_terminal_time CHECK ((status IN ('COMPLETED','DEAD','CANCELLED')) = (completed_at IS NOT NULL))
);

CREATE INDEX idx_workflow_jobs_claim ON workflow_jobs(status, next_run_at, created_at);
CREATE INDEX idx_workflow_jobs_lease ON workflow_jobs(lease_until) WHERE status = 'RUNNING';
CREATE INDEX idx_workflow_jobs_dead ON workflow_jobs(updated_at) WHERE status = 'DEAD';

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(128) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    dedup_key VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('READY', 'PUBLISHING', 'RETRY', 'PUBLISHED', 'DEAD')),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    next_run_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(255),
    lease_until TIMESTAMPTZ,
    last_error_json JSONB,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_outbox_events_dedup UNIQUE (dedup_key),
    CONSTRAINT ck_outbox_payload_object CHECK (jsonb_typeof(payload_json) = 'object'),
    CONSTRAINT ck_outbox_error_object CHECK (last_error_json IS NULL OR jsonb_typeof(last_error_json) = 'object'),
    CONSTRAINT ck_outbox_lease_pair CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_outbox_published_at CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

CREATE INDEX idx_outbox_claim ON outbox_events(status, next_run_at, created_at);
CREATE INDEX idx_outbox_lease ON outbox_events(lease_until) WHERE status = 'PUBLISHING';
