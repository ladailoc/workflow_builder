CREATE TABLE workflow_forms (
    id UUID PRIMARY KEY,
    workflow_version_id UUID NOT NULL,
    form_key VARCHAR(128) NOT NULL,
    form_type VARCHAR(32) NOT NULL,
    schema_json JSONB NOT NULL,
    schema_checksum VARCHAR(256) NOT NULL,

    CONSTRAINT fk_workflow_forms_version
        FOREIGN KEY (workflow_version_id)
        REFERENCES workflow_versions (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_workflow_forms_version_key
        UNIQUE (workflow_version_id, form_key),
    CONSTRAINT ck_workflow_forms_key
        CHECK (form_key ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_workflow_forms_type
        CHECK (form_type IN ('TICKET_FORM', 'TASK_FORM')),
    CONSTRAINT ck_workflow_forms_schema_object
        CHECK (jsonb_typeof(schema_json) = 'object'),
    CONSTRAINT ck_workflow_forms_checksum
        CHECK (btrim(schema_checksum) <> '')
);

CREATE TABLE workflow_variables (
    id UUID PRIMARY KEY,
    workflow_version_id UUID NOT NULL,
    key VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'EVENT',
    default_json JSONB,
    mutable BOOLEAN NOT NULL DEFAULT TRUE,
    sensitive BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_workflow_variables_version
        FOREIGN KEY (workflow_version_id)
        REFERENCES workflow_versions (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_workflow_variables_version_key
        UNIQUE (workflow_version_id, key),
    CONSTRAINT ck_workflow_variables_key
        CHECK (key ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_workflow_variables_type
        CHECK (type IN (
            'STRING', 'NUMBER', 'INTEGER', 'BOOLEAN', 'DATE', 'DATETIME',
            'DURATION', 'MONEY', 'USER_ID', 'USER', 'USER_LIST',
            'DEPARTMENT_ID', 'GROUP_ID', 'ENUM', 'OBJECT', 'ARRAY',
            'FILE_REF', 'FILE_LIST'
        )),
    CONSTRAINT ck_workflow_variables_scope
        CHECK (scope IN ('EVENT', 'NODE', 'MULTI_INSTANCE_ITEM'))
);

CREATE TABLE workflow_validation_runs (
    id UUID PRIMARY KEY,
    workflow_version_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    definition_checksum VARCHAR(256) NOT NULL,
    valid BOOLEAN NOT NULL,
    publishable BOOLEAN NOT NULL,
    error_count INTEGER NOT NULL,
    warning_count INTEGER NOT NULL,
    info_count INTEGER NOT NULL,
    validated_by UUID NOT NULL,
    validated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_workflow_validation_runs_version
        FOREIGN KEY (workflow_version_id)
        REFERENCES workflow_versions (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_workflow_validation_runs_revision
        CHECK (revision >= 0),
    CONSTRAINT ck_workflow_validation_runs_checksum
        CHECK (btrim(definition_checksum) <> ''),
    CONSTRAINT ck_workflow_validation_runs_counts
        CHECK (error_count >= 0 AND warning_count >= 0 AND info_count >= 0),
    CONSTRAINT ck_workflow_validation_runs_validity
        CHECK (valid = (error_count = 0)),
    CONSTRAINT ck_workflow_validation_runs_publishable
        CHECK (NOT publishable OR valid)
);

CREATE TABLE workflow_validation_issues (
    id UUID PRIMARY KEY,
    validation_run_id UUID NOT NULL,
    rule_code VARCHAR(128) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    resource_type VARCHAR(128) NOT NULL,
    resource_id UUID NOT NULL,
    field_path VARCHAR(512),
    message TEXT NOT NULL,
    suggestion TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::JSONB,

    CONSTRAINT fk_workflow_validation_issues_run
        FOREIGN KEY (validation_run_id)
        REFERENCES workflow_validation_runs (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_workflow_validation_issues_rule_code
        CHECK (rule_code ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_workflow_validation_issues_severity
        CHECK (severity IN ('ERROR', 'WARNING', 'ACK_REQUIRED_WARNING', 'INFO')),
    CONSTRAINT ck_workflow_validation_issues_resource_type
        CHECK (resource_type ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_workflow_validation_issues_field_path
        CHECK (field_path IS NULL OR btrim(field_path) <> ''),
    CONSTRAINT ck_workflow_validation_issues_message
        CHECK (btrim(message) <> ''),
    CONSTRAINT ck_workflow_validation_issues_suggestion
        CHECK (suggestion IS NULL OR btrim(suggestion) <> ''),
    CONSTRAINT ck_workflow_validation_issues_metadata_object
        CHECK (jsonb_typeof(metadata_json) = 'object')
);

CREATE INDEX ix_workflow_forms_version_type
    ON workflow_forms (workflow_version_id, form_type);

CREATE INDEX ix_workflow_variables_version_scope
    ON workflow_variables (workflow_version_id, scope);

CREATE INDEX ix_workflow_validation_runs_version_revision
    ON workflow_validation_runs (workflow_version_id, revision, validated_at DESC);

CREATE INDEX ix_workflow_validation_issues_run_severity
    ON workflow_validation_issues (validation_run_id, severity);

CREATE OR REPLACE FUNCTION guard_draft_workflow_contract_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    affected_version_id UUID;
    affected_status VARCHAR(32);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.workflow_version_id <> OLD.workflow_version_id THEN
        RAISE EXCEPTION 'Workflow contract rows cannot move between WorkflowVersions'
            USING ERRCODE = '23514';
    END IF;

    affected_version_id := CASE
        WHEN TG_OP = 'DELETE' THEN OLD.workflow_version_id
        ELSE NEW.workflow_version_id
    END;

    SELECT status
      INTO affected_status
      FROM workflow_versions
     WHERE id = affected_version_id;

    -- The parent is no longer visible during an ON DELETE CASCADE of a mutable draft.
    IF affected_status IS NULL AND TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    IF affected_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Only DRAFT WorkflowVersion contracts are mutable'
            USING ERRCODE = '23514';
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;

CREATE TRIGGER trg_workflow_forms_draft_only
BEFORE INSERT OR UPDATE OR DELETE ON workflow_forms
FOR EACH ROW EXECUTE FUNCTION guard_draft_workflow_contract_mutation();

CREATE TRIGGER trg_workflow_variables_draft_only
BEFORE INSERT OR UPDATE OR DELETE ON workflow_variables
FOR EACH ROW EXECUTE FUNCTION guard_draft_workflow_contract_mutation();
