CREATE TABLE workflow_definitions (
    id uuid PRIMARY KEY,
    key varchar(128) NOT NULL,
    name varchar(200) NOT NULL,
    description text,
    lifecycle varchar(32) NOT NULL,
    owner_id uuid NOT NULL,
    current_published_version_id uuid,
    active_draft_version_id uuid,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_workflow_definitions_key UNIQUE (key),
    CONSTRAINT ck_workflow_definitions_key
        CHECK (key ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_workflow_definitions_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_workflow_definitions_lifecycle
        CHECK (lifecycle IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT ck_workflow_definitions_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_workflow_definitions_lock_version CHECK (lock_version >= 0)
);

CREATE INDEX idx_workflow_definitions_owner_lifecycle
    ON workflow_definitions (owner_id, lifecycle);

CREATE TABLE workflow_versions (
    id uuid PRIMARY KEY,
    definition_id uuid NOT NULL,
    version_no integer NOT NULL,
    status varchar(32) NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    checksum varchar(256),
    execution_package_json jsonb,
    based_on_version_id uuid,
    rollback_of_version_id uuid,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    published_by uuid,
    published_at timestamptz,
    lock_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_workflow_versions_definition_version UNIQUE (definition_id, version_no),
    CONSTRAINT uq_workflow_versions_id_definition UNIQUE (id, definition_id),
    CONSTRAINT fk_workflow_versions_definition
        FOREIGN KEY (definition_id) REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_versions_based_on_same_definition
        FOREIGN KEY (based_on_version_id, definition_id)
        REFERENCES workflow_versions (id, definition_id) ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_versions_rollback_same_definition
        FOREIGN KEY (rollback_of_version_id, definition_id)
        REFERENCES workflow_versions (id, definition_id) ON DELETE RESTRICT,
    CONSTRAINT ck_workflow_versions_version_no CHECK (version_no > 0),
    CONSTRAINT ck_workflow_versions_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'ARCHIVED')),
    CONSTRAINT ck_workflow_versions_revision CHECK (revision >= 0),
    CONSTRAINT ck_workflow_versions_lock_version CHECK (lock_version >= 0),
    CONSTRAINT ck_workflow_versions_not_self_based
        CHECK (based_on_version_id IS NULL OR based_on_version_id <> id),
    CONSTRAINT ck_workflow_versions_not_self_rollback
        CHECK (rollback_of_version_id IS NULL OR rollback_of_version_id <> id),
    CONSTRAINT ck_workflow_versions_execution_package_object
        CHECK (execution_package_json IS NULL OR jsonb_typeof(execution_package_json) = 'object'),
    CONSTRAINT ck_workflow_versions_published_metadata_pair
        CHECK ((published_by IS NULL) = (published_at IS NULL)),
    CONSTRAINT ck_workflow_versions_published_artifact
        CHECK (
            status NOT IN ('PUBLISHED', 'SUPERSEDED')
            OR (
                checksum IS NOT NULL
                AND btrim(checksum) <> ''
                AND execution_package_json IS NOT NULL
                AND published_by IS NOT NULL
                AND published_at IS NOT NULL
            )
        )
);

CREATE UNIQUE INDEX uq_workflow_versions_one_draft
    ON workflow_versions (definition_id)
    WHERE status = 'DRAFT';

CREATE UNIQUE INDEX uq_workflow_versions_one_published
    ON workflow_versions (definition_id)
    WHERE status = 'PUBLISHED';

CREATE INDEX idx_workflow_versions_definition_status
    ON workflow_versions (definition_id, status, version_no DESC);

ALTER TABLE workflow_definitions
    ADD CONSTRAINT fk_workflow_definitions_current_published_same_definition
    FOREIGN KEY (current_published_version_id, id)
    REFERENCES workflow_versions (id, definition_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_workflow_definitions_active_draft_same_definition
    FOREIGN KEY (active_draft_version_id, id)
    REFERENCES workflow_versions (id, definition_id) ON DELETE RESTRICT;

CREATE TABLE request_types (
    id uuid PRIMARY KEY,
    key varchar(128) NOT NULL,
    name varchar(200) NOT NULL,
    description text,
    category varchar(100) NOT NULL,
    workflow_definition_id uuid NOT NULL,
    active boolean NOT NULL DEFAULT true,
    creation_policy_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    lock_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_request_types_key UNIQUE (key),
    CONSTRAINT fk_request_types_workflow_definition
        FOREIGN KEY (workflow_definition_id)
        REFERENCES workflow_definitions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_request_types_key CHECK (key ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_request_types_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_request_types_category CHECK (btrim(category) <> ''),
    CONSTRAINT ck_request_types_creation_policy_object
        CHECK (jsonb_typeof(creation_policy_json) = 'object'),
    CONSTRAINT ck_request_types_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_request_types_lock_version CHECK (lock_version >= 0)
);

CREATE INDEX idx_request_types_active_category ON request_types (active, category);
CREATE INDEX idx_request_types_workflow_definition
    ON request_types (workflow_definition_id);

CREATE FUNCTION guard_workflow_version_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'WorkflowVersion % in status % is immutable', OLD.id, OLD.status
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status <> 'DRAFT' THEN
        IF NOT (
            (OLD.status = 'PUBLISHED' AND NEW.status IN ('SUPERSEDED', 'ARCHIVED'))
            OR (OLD.status = 'SUPERSEDED' AND NEW.status = 'ARCHIVED')
        ) THEN
            RAISE EXCEPTION 'WorkflowVersion % in status % is immutable', OLD.id, OLD.status
                USING ERRCODE = '55000';
        END IF;

        IF (to_jsonb(NEW) - 'status' - 'lock_version')
            IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'lock_version') THEN
            RAISE EXCEPTION 'Published WorkflowVersion % content is immutable', OLD.id
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF NEW.status IN ('PUBLISHED', 'SUPERSEDED')
        AND (
            NEW.checksum IS NULL
            OR btrim(NEW.checksum) = ''
            OR NEW.execution_package_json IS NULL
            OR NEW.published_by IS NULL
            OR NEW.published_at IS NULL
        ) THEN
        RAISE EXCEPTION 'Published WorkflowVersion % requires frozen artifact metadata', NEW.id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_workflow_versions_immutable
BEFORE UPDATE OR DELETE ON workflow_versions
FOR EACH ROW EXECUTE FUNCTION guard_workflow_version_immutability();

CREATE FUNCTION validate_workflow_definition_version_pointers()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.current_published_version_id IS NOT NULL
        AND NOT EXISTS (
            SELECT 1
            FROM workflow_versions version
            WHERE version.id = NEW.current_published_version_id
              AND version.definition_id = NEW.id
              AND version.status = 'PUBLISHED'
        ) THEN
        RAISE EXCEPTION 'current_published_version_id must reference this definition published version'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.active_draft_version_id IS NOT NULL
        AND NOT EXISTS (
            SELECT 1
            FROM workflow_versions version
            WHERE version.id = NEW.active_draft_version_id
              AND version.definition_id = NEW.id
              AND version.status = 'DRAFT'
        ) THEN
        RAISE EXCEPTION 'active_draft_version_id must reference this definition draft version'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_workflow_definitions_validate_version_pointers
BEFORE INSERT OR UPDATE ON workflow_definitions
FOR EACH ROW EXECUTE FUNCTION validate_workflow_definition_version_pointers();
