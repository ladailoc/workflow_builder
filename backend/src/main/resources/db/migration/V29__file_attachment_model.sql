CREATE TABLE files (
    id UUID PRIMARY KEY,
    original_name VARCHAR(512) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    checksum VARCHAR(128) NOT NULL,
    storage_provider VARCHAR(64) NOT NULL,
    bucket VARCHAR(255),
    storage_key VARCHAR(1024) NOT NULL,
    scan_status VARCHAR(32) NOT NULL CHECK (scan_status IN ('PENDING_SCAN', 'CLEAN', 'QUARANTINED', 'REJECTED')),
    uploaded_by UUID NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL,
    retention_until TIMESTAMPTZ,
    sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_files_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_files_metadata_object CHECK (jsonb_typeof(metadata_json) = 'object'),
    CONSTRAINT ck_files_retention_after_upload CHECK (retention_until IS NULL OR retention_until >= uploaded_at)
);

CREATE TABLE file_links (
    id UUID PRIMARY KEY,
    file_id UUID NOT NULL REFERENCES files(id) ON DELETE RESTRICT,
    owner_type VARCHAR(32) NOT NULL CHECK (owner_type IN ('TICKET_REVISION', 'EVENT', 'NODE_EXECUTION', 'TASK_EXECUTION', 'REVISION_REQUEST')),
    owner_id UUID NOT NULL,
    field_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_file_links_owner_field_file UNIQUE (owner_type, owner_id, field_key, file_id)
);

CREATE INDEX idx_file_links_file ON file_links(file_id);
CREATE INDEX idx_file_links_owner ON file_links(owner_type, owner_id, field_key);

CREATE TRIGGER trg_files_no_delete
    BEFORE DELETE ON files
    FOR EACH ROW EXECUTE FUNCTION guard_append_only_task_history();

CREATE TRIGGER trg_file_links_append_only
    BEFORE UPDATE OR DELETE ON file_links
    FOR EACH ROW EXECUTE FUNCTION guard_append_only_task_history();
