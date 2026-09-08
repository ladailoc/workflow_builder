# Task and participant runtime persistence

Flyway V7 introduces participant resolution snapshots and human-task runtime history.

## Decisions

- Participant resolution is persisted per exact Event and NodeExecution occurrence. A composite
  foreign key prevents a snapshot from correlating a node occurrence with the wrong Event.
- `item_execution_id` is nullable in P0 so the schema remains compatible with P1 multi-instance
  persistence. Its foreign key is intentionally deferred until `node_item_executions` exists.
- A resolver snapshot stores the generic resolver type, configuration hash and JSON, resolution
  status, optional business-subject reference, actual resolved user, role, and sanitized snapshot.
  It is append-only; organization changes never rewrite a previously resolved participant.
- NodeExecution has `0..N` TaskExecution rows. There is deliberately no unique constraint on
  `node_execution_id` because one human activation may fan out to multiple tasks.
- Task technical status and business outcome are separate. Rejection is represented as
  `status=COMPLETED, outcome=REJECTED`; `REJECTED` is not a Task status.
- Task form schema and input are activation-time snapshots. Terminal form submission is stored in
  the append-only TaskDecision together with command, actor, and principal identity.
- Candidate, assignment, decision, and participant records are append-only. Task assignment changes
  are not exposed in this wave; the future command service must write assignment history and audit in
  the same short transaction.
- TaskExecution uses optimistic `lock_version`. Terminal tasks cannot be mutated, revived, or
  hard-deleted; rework creates new NodeExecution and TaskExecution occurrences.

Task commands, authorization, participant resolver execution, multi-instance aggregation, SLA
processing, routing, and audit-event writing are deferred to their dedicated capabilities.
