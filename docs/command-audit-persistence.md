# Command execution and audit persistence

Flyway V8 introduces scoped command idempotency and append-only audit history.

## Command execution contract

- Command identity is `(scope_type, scope_id, command_id)`. The database unique constraint is the
  concurrency authority; it is not replaced by a read-before-write check.
- `request_hash`, command type, actor, and optional expected version are retained with the identity.
  Reusing a scoped command ID for different input is a command conflict, not a replay.
- `PersistentCommandExecutor` reserves the identity with PostgreSQL `INSERT ... ON CONFLICT DO
  NOTHING`. A concurrent duplicate waits on the unique index and, after the winner commits, reads
  and returns the original successful result.
- Reservation, the supplied command callback, and successful result persistence share one short
  transaction. The callback must contain only transactional state work: no human wait and no
  external network call.
- The authenticated actor comes from `ActorContextProvider`; callers cannot supply an arbitrary
  actor ID. Result JSON and non-sensitive result metadata are stored for deterministic replay.
- Terminal command rows are immutable and cannot be deleted. An unsuccessful or still-running
  record is never blindly replayed as success.

Business-specific command handlers, authorization policies, and APIs are deliberately deferred.
Future handlers must perform authorization and state/version guards before mutation, write their
audit event in the same short transaction, and avoid logging secrets in result or error metadata.

## Audit contract

- Audit events retain aggregate identity, event type, actor and principal identity, correlation ID,
  optional command ID, sanitized metadata, and an UTC `Instant` occurrence time.
- Actor and principal are nullable only to support explicit system actions; authenticated commands
  should persist both identities from the security context.
- Audit rows are append-only. PostgreSQL triggers reject updates and deletes even if persistence code
  bypasses the repository abstraction.
- Aggregate timeline, event-type/time, and correlation indexes support operational investigations.

