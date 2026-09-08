CREATE TABLE revision_requests (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
    source_task_id UUID NOT NULL REFERENCES task_executions(id) ON DELETE RESTRICT,
    requested_by UUID NOT NULL,
    target_node_id UUID NOT NULL REFERENCES workflow_nodes(id) ON DELETE RESTRICT,
    cycle_id UUID NOT NULL,
    comment TEXT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_revision_request_status CHECK (status IN ('OPEN','SUBMITTED','CANCELLED')),
    CONSTRAINT ck_revision_request_completion CHECK (
      (status = 'OPEN' AND completed_at IS NULL) OR
      (status IN ('SUBMITTED','CANCELLED') AND completed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_revision_request_open_source_task
    ON revision_requests(source_task_id) WHERE status = 'OPEN';

CREATE TABLE revision_requested_fields (
    id UUID PRIMARY KEY,
    revision_request_id UUID NOT NULL REFERENCES revision_requests(id) ON DELETE RESTRICT,
    field_key VARCHAR(128) NOT NULL,
    label VARCHAR(255) NOT NULL,
    type VARCHAR(32) NOT NULL,
    required BOOLEAN NOT NULL,
    ordinal INTEGER NOT NULL,
    sensitive BOOLEAN NOT NULL,
    schema_json JSONB NOT NULL,
    CONSTRAINT uq_revision_requested_field_key UNIQUE(revision_request_id, field_key),
    CONSTRAINT uq_revision_requested_field_ordinal UNIQUE(revision_request_id, ordinal),
    CONSTRAINT ck_revision_requested_field_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_revision_requested_field_type CHECK (
      type IN ('TEXT','TEXTAREA','NUMBER','DATE','DATETIME','SELECT','BOOLEAN','FILE','FILE_LIST')
    )
);

CREATE TABLE revision_requested_values (
    requested_field_id UUID PRIMARY KEY REFERENCES revision_requested_fields(id) ON DELETE RESTRICT,
    value_json JSONB NOT NULL,
    submitted_by UUID NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_revision_requests_event ON revision_requests(event_id, created_at);
