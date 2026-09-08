CREATE TABLE tickets (
    id UUID PRIMARY KEY,
    request_type_id UUID NOT NULL,
    creator_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    data_json JSONB NOT NULL,
    data_revision BIGINT NOT NULL DEFAULT 0,
    current_revision_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    submitted_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    lock_version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_tickets_request_type
        FOREIGN KEY (request_type_id)
        REFERENCES request_types (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_tickets_status
        CHECK (status IN (
            'DRAFT', 'SUBMITTED', 'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'CANCELLED'
        )),
    CONSTRAINT ck_tickets_data_object
        CHECK (jsonb_typeof(data_json) = 'object'),
    CONSTRAINT ck_tickets_data_revision
        CHECK (data_revision >= 0),
    CONSTRAINT ck_tickets_current_revision
        CHECK (
            (data_revision = 0 AND current_revision_id IS NULL)
            OR (data_revision > 0 AND current_revision_id IS NOT NULL)
        ),
    CONSTRAINT ck_tickets_timestamps
        CHECK (
            updated_at >= created_at
            AND (submitted_at IS NULL OR submitted_at >= created_at)
            AND (completed_at IS NULL OR completed_at >= created_at)
        ),
    CONSTRAINT ck_tickets_submission_state
        CHECK ((status = 'DRAFT') = (submitted_at IS NULL)),
    CONSTRAINT ck_tickets_completion_state
        CHECK (
            (status IN ('COMPLETED', 'REJECTED', 'CANCELLED'))
            = (completed_at IS NOT NULL)
        ),
    CONSTRAINT ck_tickets_lock_version
        CHECK (lock_version >= 0)
);

CREATE TABLE ticket_revisions (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    revision_no BIGINT NOT NULL,
    data_snapshot_json JSONB NOT NULL,
    source_schema_version VARCHAR(128) NOT NULL,
    schema_checksum VARCHAR(256) NOT NULL,
    submitted_by UUID NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    change_reason TEXT,

    CONSTRAINT fk_ticket_revisions_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES tickets (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_ticket_revisions_ticket_revision
        UNIQUE (ticket_id, revision_no),
    CONSTRAINT uq_ticket_revisions_ticket_id
        UNIQUE (ticket_id, id),
    CONSTRAINT ck_ticket_revisions_number
        CHECK (revision_no > 0),
    CONSTRAINT ck_ticket_revisions_snapshot_object
        CHECK (jsonb_typeof(data_snapshot_json) = 'object'),
    CONSTRAINT ck_ticket_revisions_schema_version
        CHECK (btrim(source_schema_version) <> ''),
    CONSTRAINT ck_ticket_revisions_schema_checksum
        CHECK (btrim(schema_checksum) <> ''),
    CONSTRAINT ck_ticket_revisions_change_reason
        CHECK (change_reason IS NULL OR btrim(change_reason) <> '')
);

ALTER TABLE tickets
    ADD CONSTRAINT fk_tickets_current_revision_same_ticket
    FOREIGN KEY (id, current_revision_id)
    REFERENCES ticket_revisions (ticket_id, id)
    ON DELETE RESTRICT
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE ticket_subjects (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    subject_type VARCHAR(128) NOT NULL,
    subject_ref_id UUID NOT NULL,
    role_key VARCHAR(128) NOT NULL,
    source_field VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ticket_subjects_ticket
        FOREIGN KEY (ticket_id)
        REFERENCES tickets (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_ticket_subjects_identity
        UNIQUE (ticket_id, subject_type, subject_ref_id, role_key),
    CONSTRAINT ck_ticket_subjects_type
        CHECK (subject_type ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_ticket_subjects_role
        CHECK (role_key ~ '^[A-Z][A-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_ticket_subjects_source_field
        CHECK (source_field IS NULL OR source_field ~ '^[A-Za-z][A-Za-z0-9._-]{0,255}$')
);

CREATE INDEX ix_tickets_creator_status_created
    ON tickets (creator_id, status, created_at DESC);

CREATE INDEX ix_tickets_request_type_status
    ON tickets (request_type_id, status, created_at DESC);

CREATE INDEX ix_ticket_subjects_reference
    ON ticket_subjects (subject_type, subject_ref_id, ticket_id);

CREATE OR REPLACE FUNCTION guard_ticket_revision_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'TicketRevision % is immutable', OLD.id
            USING ERRCODE = '55000';
    END IF;

    -- Permit only the referential cascade after the owning Ticket has gone.
    IF EXISTS (SELECT 1 FROM tickets WHERE id = OLD.ticket_id) THEN
        RAISE EXCEPTION 'TicketRevision % is immutable', OLD.id
            USING ERRCODE = '55000';
    END IF;

    RETURN OLD;
END
$$;

CREATE TRIGGER trg_ticket_revisions_immutable
BEFORE UPDATE OR DELETE ON ticket_revisions
FOR EACH ROW EXECUTE FUNCTION guard_ticket_revision_immutability();
