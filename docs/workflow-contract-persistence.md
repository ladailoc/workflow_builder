# Workflow form, variable, and validation persistence

Flyway migration V4 adds the remaining design-time contract tables in this
slice: `workflow_forms`, `workflow_variables`, `workflow_validation_runs`, and
`workflow_validation_issues`.

## Ownership and immutability

- Forms, variables, and validation runs belong to one exact WorkflowVersion.
- Validation issues belong to a validation run and therefore inherit its
  WorkflowVersion ownership.
- Deleting a mutable draft cascades through its normalized contract and
  validation rows.
- Forms and variables can be inserted, updated, or deleted only while their
  WorkflowVersion is DRAFT. A database trigger protects Published, Superseded,
  and Archived version contracts from repository-level mutation.

## Contracts

- Form types are `TICKET_FORM` and `TASK_FORM`; schemas are JSON objects with a
  required checksum and a key unique within the version.
- Variables use the shared canonical value type, explicit scope, optional JSON
  default, and independent `mutable` and `sensitive` flags. Variable keys are
  unique within the version.
- Validation runs snapshot the draft revision and definition checksum, result
  flags, summary counts, actor, and UTC validation instant.
- Validation issues carry stable rule codes, severity, resource locator, field
  path, message, optional suggestion, and object-shaped metadata.

This capability intentionally contains persistence entities, repositories, and
DTO contracts only. Validation rules, graph compilation, acknowledgement flow,
and publish orchestration are deferred to their dedicated capabilities.
