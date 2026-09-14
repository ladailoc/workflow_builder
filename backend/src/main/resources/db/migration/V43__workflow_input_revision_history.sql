-- v2.4.1 remediation: REQUEST_REVISION must be able to produce a NEW immutable
-- WorkflowInputSnapshot without mutating the Event's current-pointer row, and the
-- Category remap policy must be explicit design-time configuration.

CREATE TABLE event_workflow_input_revisions (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
    category_version_id UUID NOT NULL REFERENCES ticket_category_versions(id) ON DELETE RESTRICT,
    form_submission_id UUID NOT NULL REFERENCES form_submissions(id) ON DELETE RESTRICT,
    ticket_revision_id UUID REFERENCES ticket_revisions(id) ON DELETE RESTRICT,
    input_revision BIGINT NOT NULL,
    inputs_json JSONB NOT NULL,
    mapping_checksum VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_event_input_revisions_number UNIQUE(event_id, input_revision),
    CONSTRAINT ck_event_input_revisions_number CHECK (input_revision > 0),
    CONSTRAINT ck_event_input_revisions_object CHECK (jsonb_typeof(inputs_json) = 'object')
);
CREATE INDEX ix_event_input_revisions_event ON event_workflow_input_revisions(event_id, input_revision DESC);

CREATE OR REPLACE FUNCTION guard_v43_input_revision_immutable()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'WorkflowInputRevision history is immutable' USING ERRCODE = '55000';
END $$;

CREATE TRIGGER trg_event_workflow_input_revisions_immutable
BEFORE UPDATE OR DELETE ON event_workflow_input_revisions
FOR EACH ROW EXECUTE FUNCTION guard_v43_input_revision_immutable();

-- Current-pointer row for legacy creates (revision 1) backfills its history entry so
-- every Event resolves its full input revision chain from one table.
INSERT INTO event_workflow_input_revisions(id, event_id, category_version_id, form_submission_id,
                                           ticket_revision_id, input_revision, inputs_json,
                                           mapping_checksum, created_at)
SELECT gen_random_uuid(), s.event_id, s.category_version_id, s.form_submission_id,
       NULL, s.input_revision, s.inputs_json, s.mapping_checksum, s.created_at
FROM event_workflow_input_snapshots s
ON CONFLICT (event_id, input_revision) DO NOTHING;

-- A submitted REQUEST_REVISION records the business revision and the mapped WorkflowInputRevision
-- it produced, so the rework occurrence is traceably bound to one intended input revision.
ALTER TABLE revision_requests ADD COLUMN form_submission_id UUID REFERENCES form_submissions(id) ON DELETE RESTRICT;
ALTER TABLE revision_requests ADD COLUMN input_revision BIGINT;
ALTER TABLE revision_requests ADD COLUMN ticket_revision_id UUID REFERENCES ticket_revisions(id) ON DELETE RESTRICT;
