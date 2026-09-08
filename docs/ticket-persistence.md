# Ticket persistence boundary

Flyway V5 introduces the canonical business aggregate: `tickets`, immutable
`ticket_revisions`, and normalized `ticket_subjects`.

## Decisions

- A Ticket is a business request and does not bind a WorkflowVersion. That
  binding belongs to Event creation in the runtime capability.
- `data_json` is an object-shaped JSONB document controlled by the published
  Ticket Form schema. There is no custom-field table and no database column is
  generated for a dynamic field.
- The first submitted snapshot is revision 1. Every later business-data change
  creates the next immutable revision and atomically advances
  `tickets.current_revision_id` and `data_revision`.
- A composite foreign key guarantees that the current revision belongs to the
  same Ticket. PostgreSQL uniqueness and an immutable trigger protect revision
  history even when repositories are bypassed.
- Ticket subjects are current, normalized business targets. Their type,
  reference, business role, and source form field are relational and indexed;
  they are intentionally not participants or assignees.
- Commands derive creator/submitting actor identity from `ActorContext`. Client
  DTOs contain no actor identifier. Creator-or-admin authorization and expected
  aggregate versions protect reads and mutations until the richer visibility
  policy capability is introduced.

Ticket Form validation, RequestType creation-policy evaluation, Event creation,
runtime participant resolution, audit-event persistence, and HTTP controllers
remain outside this persistence capability.
