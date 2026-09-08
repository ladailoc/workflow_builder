# Shared domain primitives

Canonical domain values live below `com.fpt.workflow.shared.domain`. Aggregate,
command, and correlation identifiers are UUID-backed value objects. Mutable
aggregates expose `AggregateVersion`; commands carry a separate
`ExpectedVersion`, and `OptimisticVersionGuard` raises a Spring-compatible
optimistic locking failure on mismatch so the shared API contract returns 409.

Lifecycle timestamps use `Instant` exclusively and reject an `updatedAt` before
`createdAt`. Canonical lifecycle enums implement the `LifecycleState` marker.
`BusinessOutcome` is an independent, extensible value and must be persisted or
transported in an outcome field separate from status. In particular, rejecting
a task means `TaskStatus.COMPLETED` with `BusinessOutcome.REJECTED`; there is no
`TaskStatus.REJECTED`.

`TransitionGuard` deliberately does not own workflow transition matrices. The
aggregate-owning domain supplies the allowed targets for its current state, and
the guard rejects same-state or non-allow-listed transitions. This prevents the
shared layer from becoming a premature workflow engine.

Pagination is zero-based and bounded to 200 items. Sort property syntax is
validated, but each API/application service must still map public sort keys to
an explicit allow-list of fields; callers must never concatenate the property
directly into SQL.

A generic typed JSON value was intentionally not introduced yet. The platform
specification requires schema-aware form and runtime values, so that abstraction
belongs with the canonical value-type/schema contract rather than a premature
wrapper around arbitrary `Object`, `Map`, or `JsonNode`.
