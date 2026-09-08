CREATE TABLE connector_definitions (
    id UUID PRIMARY KEY,
    key VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    connector_type VARCHAR(64) NOT NULL,
    handler_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    credential_ref VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_connector_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'DEPRECATED'))
);

CREATE TABLE connector_actions (
    id UUID PRIMARY KEY,
    connector_id UUID NOT NULL REFERENCES connector_definitions(id) ON DELETE RESTRICT,
    action_key VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_connector_action_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'DEPRECATED')),
    CONSTRAINT uq_connector_action UNIQUE (connector_id, action_key)
);

CREATE TABLE connector_action_versions (
    id UUID PRIMARY KEY,
    connector_action_id UUID NOT NULL REFERENCES connector_actions(id) ON DELETE RESTRICT,
    version_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
    input_schema_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_schema_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    execution_config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    retry_policy_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    idempotency_policy_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_mapping_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    permission_policy_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_connector_action_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED')),
    CONSTRAINT ck_connector_action_version_no CHECK (version_no >= 1),
    CONSTRAINT uq_connector_action_version UNIQUE (connector_action_id, version_no)
);

CREATE INDEX ix_connector_action_versions_action_status ON connector_action_versions(connector_action_id, status);

CREATE OR REPLACE FUNCTION trg_guard_connector_action_version_immutable()
RETURNS TRIGGER AS $$
BEGIN
    IF (OLD.status IN ('PUBLISHED', 'DEPRECATED')) THEN
        IF (NEW.version_no <> OLD.version_no
            OR NEW.input_schema_json <> OLD.input_schema_json
            OR NEW.output_schema_json <> OLD.output_schema_json
            OR NEW.execution_config_json <> OLD.execution_config_json
            OR NEW.retry_policy_json <> OLD.retry_policy_json
            OR NEW.idempotency_policy_json <> OLD.idempotency_policy_json
            OR NEW.error_mapping_json <> OLD.error_mapping_json) THEN
            RAISE EXCEPTION 'A published or deprecated connector action version is immutable and cannot be updated';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_connector_action_version_immutability
BEFORE UPDATE ON connector_action_versions
FOR EACH ROW
EXECUTE FUNCTION trg_guard_connector_action_version_immutable();
