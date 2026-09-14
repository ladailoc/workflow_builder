-- v2.4.1 expand phase: introduce independent Form, typed Workflow input/state,
-- and versioned Ticket Category binding while retaining legacy request_types/workflow_forms reads.

CREATE TABLE forms (
    id UUID PRIMARY KEY,
    key VARCHAR(128) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    lifecycle VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    current_published_version_id UUID,
    active_draft_version_id UUID,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_forms_key CHECK (key ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_forms_lifecycle CHECK (lifecycle IN ('ACTIVE','SUSPENDED','ARCHIVED'))
);

CREATE TABLE form_versions (
    id UUID PRIMARY KEY,
    form_id UUID NOT NULL REFERENCES forms(id) ON DELETE RESTRICT,
    version_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    checksum VARCHAR(256),
    schema_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    compiled_schema_json JSONB,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_by UUID,
    published_at TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_form_versions_number UNIQUE(form_id, version_no),
    CONSTRAINT uq_form_versions_id_form UNIQUE(id, form_id),
    CONSTRAINT ck_form_versions_number CHECK (version_no > 0),
    CONSTRAINT ck_form_versions_status CHECK (status IN ('DRAFT','PUBLISHED','SUPERSEDED','ARCHIVED')),
    CONSTRAINT ck_form_versions_schema CHECK (jsonb_typeof(schema_json) = 'object')
);

ALTER TABLE forms ADD CONSTRAINT fk_forms_current_published
    FOREIGN KEY (current_published_version_id, id) REFERENCES form_versions(id, form_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE forms ADD CONSTRAINT fk_forms_active_draft
    FOREIGN KEY (active_draft_version_id, id) REFERENCES form_versions(id, form_id) DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX uq_form_versions_one_draft ON form_versions(form_id) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX uq_form_versions_one_published ON form_versions(form_id) WHERE status = 'PUBLISHED';

CREATE TABLE form_fields (
    id UUID PRIMARY KEY,
    form_version_id UUID NOT NULL REFERENCES form_versions(id) ON DELETE RESTRICT,
    field_key VARCHAR(128) NOT NULL,
    label VARCHAR(256) NOT NULL,
    description TEXT,
    placeholder TEXT,
    ordinal INTEGER NOT NULL,
    type_json JSONB NOT NULL,
    default_json JSONB,
    sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    requirement_json JSONB NOT NULL DEFAULT '{"mode":"NEVER"}'::jsonb,
    visibility_json JSONB NOT NULL DEFAULT '{"mode":"ALWAYS"}'::jsonb,
    editability_json JSONB NOT NULL DEFAULT '{"mode":"EDITABLE"}'::jsonb,
    validation_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    options_json JSONB,
    semantic_tag VARCHAR(128),
    participant_capable BOOLEAN NOT NULL DEFAULT FALSE,
    business_subject BOOLEAN NOT NULL DEFAULT FALSE,
    filterable BOOLEAN NOT NULL DEFAULT FALSE,
    reportable BOOLEAN NOT NULL DEFAULT FALSE,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_form_fields_key UNIQUE(form_version_id, field_key),
    CONSTRAINT ck_form_fields_key CHECK (field_key ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_form_fields_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_form_fields_type CHECK (canonical_type_descriptor_is_valid(type_json))
);

CREATE TABLE form_submissions (
    id UUID PRIMARY KEY,
    form_version_id UUID NOT NULL REFERENCES form_versions(id) ON DELETE RESTRICT,
    context_type VARCHAR(32) NOT NULL,
    context_id UUID,
    data_json JSONB NOT NULL,
    schema_checksum VARCHAR(256) NOT NULL,
    submitted_by UUID NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_form_submissions_context CHECK (context_type IN ('TICKET_CREATE','TASK','REVISION_REQUEST','EXTERNAL')),
    CONSTRAINT ck_form_submissions_data CHECK (jsonb_typeof(data_json) = 'object')
);

CREATE TABLE workflow_inputs (
    id UUID PRIMARY KEY,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions(id) ON DELETE RESTRICT,
    input_key VARCHAR(128) NOT NULL,
    semantic_tag VARCHAR(128),
    type_json JSONB NOT NULL,
    required BOOLEAN NOT NULL,
    default_json JSONB,
    schema_json JSONB,
    sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    description TEXT,
    ordinal INTEGER NOT NULL,
    CONSTRAINT uq_workflow_inputs_key UNIQUE(workflow_version_id, input_key),
    CONSTRAINT ck_workflow_inputs_key CHECK (input_key ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_workflow_inputs_type CHECK (canonical_type_descriptor_is_valid(type_json)),
    CONSTRAINT ck_workflow_inputs_ordinal CHECK (ordinal >= 0)
);

CREATE TABLE workflow_states (
    id UUID PRIMARY KEY,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions(id) ON DELETE RESTRICT,
    state_key VARCHAR(128) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    state_group VARCHAR(128),
    terminal BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_workflow_states_key UNIQUE(workflow_version_id, state_key),
    CONSTRAINT ck_workflow_states_key CHECK (state_key ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_workflow_states_order CHECK (display_order >= 0)
);

CREATE TABLE ticket_categories (
    id UUID PRIMARY KEY,
    key VARCHAR(128) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    category_group VARCHAR(100),
    icon VARCHAR(256),
    lifecycle VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    current_published_version_id UUID,
    active_draft_version_id UUID,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_ticket_categories_key CHECK (key ~ '^[A-Za-z][A-Za-z0-9._-]{0,127}$'),
    CONSTRAINT ck_ticket_categories_lifecycle CHECK (lifecycle IN ('ACTIVE','SUSPENDED','ARCHIVED'))
);

CREATE TABLE ticket_category_versions (
    id UUID PRIMARY KEY,
    ticket_category_id UUID NOT NULL REFERENCES ticket_categories(id) ON DELETE RESTRICT,
    version_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    form_version_id UUID NOT NULL REFERENCES form_versions(id) ON DELETE RESTRICT,
    workflow_version_id UUID NOT NULL REFERENCES workflow_versions(id) ON DELETE RESTRICT,
    creation_policy_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    revision BIGINT NOT NULL DEFAULT 0,
    checksum VARCHAR(256),
    mapping_checksum VARCHAR(256),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_by UUID,
    published_at TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_ticket_category_versions_number UNIQUE(ticket_category_id, version_no),
    CONSTRAINT uq_ticket_category_versions_id_category UNIQUE(id, ticket_category_id),
    CONSTRAINT ck_ticket_category_versions_number CHECK (version_no > 0),
    CONSTRAINT ck_ticket_category_versions_status CHECK (status IN ('DRAFT','PUBLISHED','SUPERSEDED','ARCHIVED')),
    CONSTRAINT ck_ticket_category_versions_policy CHECK (jsonb_typeof(creation_policy_json) = 'object')
);

ALTER TABLE ticket_categories ADD CONSTRAINT fk_ticket_categories_current_published
    FOREIGN KEY (current_published_version_id, id) REFERENCES ticket_category_versions(id, ticket_category_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE ticket_categories ADD CONSTRAINT fk_ticket_categories_active_draft
    FOREIGN KEY (active_draft_version_id, id) REFERENCES ticket_category_versions(id, ticket_category_id) DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX uq_ticket_category_versions_one_draft ON ticket_category_versions(ticket_category_id) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX uq_ticket_category_versions_one_published ON ticket_category_versions(ticket_category_id) WHERE status = 'PUBLISHED';

CREATE TABLE ticket_category_mappings (
    id UUID PRIMARY KEY,
    category_version_id UUID NOT NULL REFERENCES ticket_category_versions(id) ON DELETE RESTRICT,
    target_workflow_input_id UUID NOT NULL REFERENCES workflow_inputs(id) ON DELETE RESTRICT,
    source_type VARCHAR(32) NOT NULL,
    source_form_field_id UUID REFERENCES form_fields(id) ON DELETE RESTRICT,
    source_expression_json JSONB,
    constant_json JSONB,
    default_json JSONB,
    on_missing VARCHAR(32) NOT NULL DEFAULT 'ERROR',
    transform_json JSONB,
    ordinal INTEGER NOT NULL,
    CONSTRAINT uq_ticket_category_mappings_target UNIQUE(category_version_id, target_workflow_input_id),
    CONSTRAINT ck_ticket_category_mappings_source CHECK (source_type IN ('FORM_FIELD','SYSTEM_CONTEXT','CONSTANT','EXPRESSION','DEFAULT')),
    CONSTRAINT ck_ticket_category_mappings_missing CHECK (on_missing IN ('ERROR','USE_DEFAULT','NULL')),
    CONSTRAINT ck_ticket_category_mappings_ordinal CHECK (ordinal >= 0)
);

ALTER TABLE tickets ALTER COLUMN request_type_id DROP NOT NULL;
ALTER TABLE tickets ADD COLUMN ticket_category_version_id UUID REFERENCES ticket_category_versions(id) ON DELETE RESTRICT;
ALTER TABLE tickets ADD COLUMN current_form_submission_id UUID REFERENCES form_submissions(id) ON DELETE RESTRICT;
ALTER TABLE tickets ADD COLUMN current_business_state_key VARCHAR(128);
ALTER TABLE tickets ADD COLUMN current_state_updated_at TIMESTAMPTZ;
CREATE INDEX ix_tickets_category_status ON tickets(ticket_category_version_id, status, created_at DESC);

CREATE TABLE event_workflow_input_snapshots (
    event_id UUID PRIMARY KEY REFERENCES events(id) ON DELETE RESTRICT,
    category_version_id UUID NOT NULL REFERENCES ticket_category_versions(id) ON DELETE RESTRICT,
    form_submission_id UUID NOT NULL REFERENCES form_submissions(id) ON DELETE RESTRICT,
    input_revision BIGINT NOT NULL DEFAULT 1,
    inputs_json JSONB NOT NULL,
    mapping_checksum VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_event_input_revision CHECK (input_revision > 0),
    CONSTRAINT ck_event_inputs_object CHECK (jsonb_typeof(inputs_json) = 'object')
);

CREATE TABLE ticket_state_history (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE RESTRICT,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
    state_key VARCHAR(128) NOT NULL,
    source_node_execution_id UUID REFERENCES node_executions(id) ON DELETE RESTRICT,
    entered_at TIMESTAMPTZ NOT NULL,
    exited_at TIMESTAMPTZ,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_ticket_state_history_key CHECK (state_key ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_ticket_state_history_time CHECK (exited_at IS NULL OR exited_at >= entered_at)
);
CREATE INDEX ix_ticket_state_history_ticket ON ticket_state_history(ticket_id, entered_at DESC);

-- Compatibility backfill: each legacy workflow-owned TicketForm becomes an independent
-- published FormVersion, and each active RequestType receives a published CategoryVersion.
INSERT INTO forms(id, key, name, description, lifecycle, created_by, created_at, updated_at)
SELECT (md5('form:' || wf.id::text))::uuid,
       'legacy_' || replace(wf.id::text, '-', ''),
       rt.name || ' Form',
       'Migrated from workflow_forms ' || wf.id::text,
       'ACTIVE', wv.created_by, wv.created_at, wv.created_at
FROM workflow_forms wf
JOIN workflow_versions wv ON wv.id = wf.workflow_version_id
JOIN request_types rt ON rt.workflow_definition_id = wv.definition_id
WHERE wf.form_type = 'TICKET_FORM'
ON CONFLICT (key) DO NOTHING;

INSERT INTO form_versions(id, form_id, version_no, status, revision, checksum, schema_json,
                          compiled_schema_json, created_by, created_at, published_by, published_at)
SELECT (md5('form-version:' || wf.id::text))::uuid,
       (md5('form:' || wf.id::text))::uuid,
       1, 'PUBLISHED', 0, wf.schema_checksum, wf.schema_json, wf.schema_json,
       wv.created_by, wv.created_at, COALESCE(wv.published_by, wv.created_by), COALESCE(wv.published_at, wv.created_at)
FROM workflow_forms wf
JOIN workflow_versions wv ON wv.id = wf.workflow_version_id
WHERE wf.form_type = 'TICKET_FORM'
ON CONFLICT (id) DO NOTHING;

UPDATE forms f SET current_published_version_id = fv.id
FROM form_versions fv WHERE fv.form_id = f.id AND fv.status = 'PUBLISHED';

INSERT INTO form_fields(id, form_version_id, field_key, label, ordinal, type_json, sensitive,
                        requirement_json, visibility_json, editability_json, validation_json,
                        semantic_tag, participant_capable, business_subject, filterable, reportable, metadata_json)
SELECT (md5('form-field:' || wf.id::text || ':' || ((field.value::jsonb)->>'fieldId')))::uuid,
       (md5('form-version:' || wf.id::text))::uuid,
       (field.value::jsonb)->>'fieldId', COALESCE((field.value::jsonb)->>'label', (field.value::jsonb)->>'fieldId'), field.ordinality - 1,
       COALESCE((field.value::jsonb)->'type', '{"type":"STRING","nullable":true}'::jsonb),
       COALESCE(((field.value::jsonb)->>'sensitive')::boolean, false),
       COALESCE((field.value::jsonb)->'requirement', '{"mode":"NEVER"}'::jsonb),
       COALESCE((field.value::jsonb)->'visibility', '{"mode":"ALWAYS"}'::jsonb),
       COALESCE((field.value::jsonb)->'editability', '{"mode":"EDITABLE"}'::jsonb),
       COALESCE((field.value::jsonb)->'validation', '{}'::jsonb),
       (field.value::jsonb)->'semanticMetadata'->>'businessConcept',
       COALESCE(((field.value::jsonb)->'semanticMetadata'->>'participantCapable')::boolean, false),
       COALESCE(((field.value::jsonb)->'semanticMetadata'->>'businessSubject')::boolean, false),
       COALESCE(((field.value::jsonb)->'semanticMetadata'->>'filterable')::boolean, false),
       COALESCE(((field.value::jsonb)->'semanticMetadata'->>'reportable')::boolean, false),
       COALESCE((field.value::jsonb)->'metadata', '{}'::jsonb)
FROM workflow_forms wf
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(wf.schema_json->'fields','[]'::jsonb)) WITH ORDINALITY AS field(value, ordinality)
WHERE wf.form_type = 'TICKET_FORM' AND (field.value::jsonb) ? 'fieldId'
ON CONFLICT (form_version_id, field_key) DO NOTHING;

INSERT INTO workflow_inputs(id, workflow_version_id, input_key, semantic_tag, type_json, required,
                            default_json, sensitive, description, ordinal)
SELECT (md5('workflow-input:' || wf.workflow_version_id::text || ':' || ((field.value::jsonb)->>'fieldId')))::uuid,
       wf.workflow_version_id, (field.value::jsonb)->>'fieldId',
       (field.value::jsonb)->'semanticMetadata'->>'businessConcept',
       COALESCE((field.value::jsonb)->'type', '{"type":"STRING","nullable":true}'::jsonb),
       COALESCE((field.value::jsonb)->'requirement'->>'mode' = 'ALWAYS', false),
       (field.value::jsonb)->'defaultValue', COALESCE(((field.value::jsonb)->>'sensitive')::boolean, false),
       (field.value::jsonb)->>'description', field.ordinality - 1
FROM workflow_forms wf
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(wf.schema_json->'fields','[]'::jsonb)) WITH ORDINALITY AS field(value, ordinality)
WHERE wf.form_type = 'TICKET_FORM' AND (field.value::jsonb) ? 'fieldId'
ON CONFLICT (workflow_version_id, input_key) DO NOTHING;

INSERT INTO ticket_categories(id, key, name, description, category_group, lifecycle, created_by, created_at, updated_at)
SELECT (md5('ticket-category:' || rt.id::text))::uuid, rt.key, rt.name, rt.description, rt.category,
       CASE WHEN rt.active THEN 'ACTIVE' ELSE 'SUSPENDED' END, rt.id, rt.created_at, rt.updated_at
FROM request_types rt ON CONFLICT (key) DO NOTHING;

INSERT INTO ticket_category_versions(id, ticket_category_id, version_no, status, form_version_id,
                                     workflow_version_id, creation_policy_json, revision, checksum,
                                     mapping_checksum, created_by, created_at, published_by, published_at)
SELECT (md5('category-version:' || rt.id::text || ':' || wv.id::text))::uuid,
       (md5('ticket-category:' || rt.id::text))::uuid,
       wv.version_no,
       CASE WHEN wv.id = wd.current_published_version_id THEN 'PUBLISHED' ELSE 'SUPERSEDED' END,
       (md5('form-version:' || wf.id::text))::uuid,
       wv.id, rt.creation_policy_json, 0,
       md5(rt.id::text || ':' || wv.id::text || ':' || wf.schema_checksum),
       md5('legacy-identity:' || wf.id::text), rt.id, wv.created_at,
       COALESCE(wv.published_by, wv.created_by), COALESCE(wv.published_at, wv.created_at)
FROM request_types rt
JOIN workflow_definitions wd ON wd.id = rt.workflow_definition_id
JOIN workflow_versions wv ON wv.definition_id = wd.id AND wv.status IN ('PUBLISHED','SUPERSEDED')
JOIN workflow_forms wf ON wf.workflow_version_id = wv.id AND wf.form_type = 'TICKET_FORM'
ON CONFLICT (id) DO NOTHING;

UPDATE ticket_categories tc SET current_published_version_id = tcv.id
FROM request_types rt
JOIN workflow_definitions wd ON wd.id = rt.workflow_definition_id
JOIN ticket_category_versions tcv ON tcv.id = (md5('category-version:' || rt.id::text || ':' || wd.current_published_version_id::text))::uuid
WHERE tc.id = (md5('ticket-category:' || rt.id::text))::uuid;

INSERT INTO ticket_category_mappings(id, category_version_id, target_workflow_input_id, source_type,
                                     source_form_field_id, on_missing, ordinal)
SELECT (md5('category-mapping:' || tcv.id::text || ':' || wi.id::text))::uuid,
       tcv.id, wi.id, 'FORM_FIELD', ff.id, CASE WHEN wi.required THEN 'ERROR' ELSE 'NULL' END, wi.ordinal
FROM ticket_category_versions tcv
JOIN workflow_inputs wi ON wi.workflow_version_id = tcv.workflow_version_id
JOIN form_fields ff ON ff.form_version_id = tcv.form_version_id AND ff.field_key = wi.input_key
ON CONFLICT (category_version_id, target_workflow_input_id) DO NOTHING;

UPDATE tickets t SET ticket_category_version_id = tcv.id
FROM ticket_categories tc
JOIN ticket_category_versions tcv ON tcv.ticket_category_id = tc.id AND tcv.status = 'PUBLISHED'
WHERE tc.id = (md5('ticket-category:' || t.request_type_id::text))::uuid
  AND t.ticket_category_version_id IS NULL;

CREATE OR REPLACE FUNCTION guard_v241_published_artifacts()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.status = 'PUBLISHED' AND NEW.status = 'SUPERSEDED'
       AND to_jsonb(NEW) - 'status' - 'lock_version' = to_jsonb(OLD) - 'status' - 'lock_version' THEN
        RETURN NEW;
    END IF;
    IF OLD.status IN ('PUBLISHED','SUPERSEDED','ARCHIVED') THEN
        RAISE EXCEPTION 'Published versioned artifact % is immutable', OLD.id USING ERRCODE = '55000';
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

CREATE TRIGGER trg_form_versions_immutable BEFORE UPDATE OR DELETE ON form_versions
FOR EACH ROW EXECUTE FUNCTION guard_v241_published_artifacts();
CREATE TRIGGER trg_ticket_category_versions_immutable BEFORE UPDATE OR DELETE ON ticket_category_versions
FOR EACH ROW EXECUTE FUNCTION guard_v241_published_artifacts();

CREATE OR REPLACE FUNCTION guard_v241_published_children()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent_status VARCHAR(32);
BEGIN
    IF TG_TABLE_NAME = 'form_fields' THEN
        SELECT status INTO parent_status FROM form_versions WHERE id = COALESCE(NEW.form_version_id, OLD.form_version_id);
    ELSIF TG_TABLE_NAME IN ('workflow_inputs','workflow_states') THEN
        SELECT status INTO parent_status FROM workflow_versions WHERE id = COALESCE(NEW.workflow_version_id, OLD.workflow_version_id);
    ELSE
        SELECT status INTO parent_status FROM ticket_category_versions WHERE id = COALESCE(NEW.category_version_id, OLD.category_version_id);
    END IF;
    IF parent_status IN ('PUBLISHED','SUPERSEDED','ARCHIVED') THEN
        RAISE EXCEPTION 'Child rows of a published artifact are immutable' USING ERRCODE = '55000';
    END IF;
    RETURN COALESCE(NEW, OLD);
END $$;

CREATE TRIGGER trg_form_fields_published_guard BEFORE INSERT OR UPDATE OR DELETE ON form_fields FOR EACH ROW EXECUTE FUNCTION guard_v241_published_children();
CREATE TRIGGER trg_workflow_inputs_published_guard BEFORE INSERT OR UPDATE OR DELETE ON workflow_inputs FOR EACH ROW EXECUTE FUNCTION guard_v241_published_children();
CREATE TRIGGER trg_workflow_states_published_guard BEFORE INSERT OR UPDATE OR DELETE ON workflow_states FOR EACH ROW EXECUTE FUNCTION guard_v241_published_children();
CREATE TRIGGER trg_category_mappings_published_guard BEFORE INSERT OR UPDATE OR DELETE ON ticket_category_mappings FOR EACH ROW EXECUTE FUNCTION guard_v241_published_children();

CREATE OR REPLACE FUNCTION guard_v241_event_input_snapshot()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Event WorkflowInputSnapshot is immutable' USING ERRCODE = '55000';
END $$;

CREATE TRIGGER trg_event_workflow_input_snapshot_immutable
BEFORE UPDATE OR DELETE ON event_workflow_input_snapshots
FOR EACH ROW EXECUTE FUNCTION guard_v241_event_input_snapshot();
