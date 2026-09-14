-- Supports the version-scoped dynamic Ticket reporting query without blanket JSONB indexing.
CREATE INDEX ix_ticket_revisions_source_schema_ticket
    ON ticket_revisions (source_schema_version, ticket_id);

CREATE INDEX ix_tickets_reporting_stable_order
    ON tickets (created_at DESC, id DESC)
    WHERE current_revision_id IS NOT NULL;
