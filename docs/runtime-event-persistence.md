# Event and NodeExecution persistence boundary

Flyway V6 introduces `events` and `node_executions` as runtime history bound
to an exact published WorkflowVersion and an exact submitted TicketRevision.

## Decisions

- Event technical lifecycle and business outcome are separate columns.
  `REJECTED` is an outcome, not an Event status.
- Root Events reference themselves through `root_event_id`; child Events carry
  root Event, parent Event, and parent NodeExecution identities. Composite
  foreign keys prevent history links from crossing Tickets.
- A partial unique index permits at most one active root Event per Ticket while
  allowing child Events and terminal root history.
- Every NodeExecution is an occurrence. There is deliberately no unique
  constraint on `(event_id, node_definition_id)`; rework, loop, parallel, and
  multi-instance paths create new rows.
- `activation_key` is the logical idempotency identity. Cycle, iteration, path,
  item, split, and join correlation are persisted independently.
- A database ownership trigger verifies that NodeDefinition belongs to the
  Event's exact WorkflowVersion and that the starting TicketRevision belongs
  to the Event Ticket.
- Runtime rows use optimistic `lock_version`. Terminal NodeExecution and
  terminal Event history cannot be mutated or hard-deleted. Event `FAILED`
  remains recoverable to `RUNNING`; terminal NodeExecution occurrences are
  never revived.
- `variables_json` is only the declared event-variable store. It is not a
  persisted giant EventContext; ticket, organization, node snapshots, and
  future participant/task data remain normalized.

The execution engine, routing decisions, activation-token consumption,
participant resolution, task creation, command API, and audit-event writer are
explicitly deferred to their dedicated capabilities.
