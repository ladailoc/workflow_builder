# Request catalog and workflow definition persistence

Flyway migration V2 introduces `request_types`, `workflow_definitions`, and
`workflow_versions` as the first P0 domain tables. All identifiers are PostgreSQL
`uuid`, timestamps are `timestamptz`, dynamic policies/packages are `jsonb`, and
mutable rows use a non-negative `lock_version` mapped with JPA `@Version`.

## Referential integrity

- Request types and workflow versions reference definitions with
  `ON DELETE RESTRICT`; workflow history is never cascade-deleted.
- Definition draft/published pointers use composite foreign keys against
  `(workflow_version.id, workflow_version.definition_id)`, so a pointer cannot
  cross definition boundaries.
- `based_on_version_id` and `rollback_of_version_id` use the same composite-FK
  rule and cannot self-reference.
- Partial unique indexes allow at most one DRAFT and one PUBLISHED version per
  definition. `(definition_id, version_no)` is unique.

Draft version numbers are allocated by `WorkflowVersionService` while holding a
pessimistic row lock on the parent definition. Callers cannot supply or edit a
version number, and concurrent draft creation cannot allocate the same number.

Pointer validation also checks that `active_draft_version_id` targets a DRAFT
and `current_published_version_id` targets a PUBLISHED version. This complements
the composite foreign keys; publish operations must update statuses and pointers
within one short transaction.

## Immutability and services

Only DRAFT versions can be edited or deleted through `WorkflowVersionService`.
A database trigger blocks payload updates and deletion for published,
superseded, and archived versions. It reserves controlled lifecycle-only
transitions from PUBLISHED to SUPERSEDED/ARCHIVED and from SUPERSEDED to ARCHIVED
for the later publish/archive service.

`WorkflowDefinitionService`, `WorkflowVersionService`, and `RequestTypeService`
provide transactional create/update/read and paginated query operations.
Request types and draft versions support guarded deletion. Definitions use the
ARCHIVED lifecycle instead of generic hard deletion. Creation metadata is
derived from authenticated `ActorContext`; callers do not provide `createdBy`.
Definition and request-catalog mutations require WORKFLOW_OWNER or ADMIN;
draft-version mutations also allow WORKFLOW_EDITOR. Resource ownership and
visibility remain a later policy layer.

Publish orchestration and REST controllers are not part of this capability.
