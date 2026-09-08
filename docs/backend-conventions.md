# Backend platform conventions

## REST and error contract

All business controllers are mounted under `/api/v1`. State changes use explicit
command endpoints; a generic `PATCH status` endpoint is prohibited.

Errors use `application/problem+json` with RFC 9457 core fields plus stable
extensions:

- `code`: stable machine-readable platform or domain error code.
- `correlationId`: propagated UUID when the client supplies a valid UUID;
  otherwise generated server-side.
- `requestId`: a new UUID for every HTTP request.
- `timestamp`: UTC `Instant` serialized as ISO-8601.
- `errors`: field/object validation errors without rejected values.

| HTTP | Meaning |
| --- | --- |
| 400 | Malformed JSON, invalid argument type, or invalid request syntax |
| 403 | Authenticated actor lacks permission |
| 404 | Requested resource does not exist or is not visible |
| 409 | Invalid command state, stale expected version, or optimistic-lock conflict |
| 422 | Syntactically valid command or DTO violates validation/business preconditions |

Unexpected failures return a generic 500 problem. Stack traces and exception
details remain server-side and logs include both IDs.

## Transaction rules

`@TransactionalCommand` marks one short critical state transition. It rolls back
for checked and unchecked exceptions and has a bounded timeout. Services must
authorize and validate state before mutation, persist the transition, enqueue any
durable continuation, and commit promptly.

Never wait for a human, invoke an external network service, sleep, poll, or hold a
worker lease loop inside a database transaction. External integration follows
TX1 intention/attempt → commit → network call → TX2 result/continuation.

`@TransactionalQuery` marks read-only service operations. Controllers do not own
transaction boundaries.

## Time, identifiers, JSON, and secrets

- Persisted/external timestamps come from `PlatformClock` and use `Instant`.
- IDs come from injected `UuidGenerator`; the default implementation generates
  RFC 4122 UUID v4 values.
- JSON serializes dates as ISO-8601, omits null properties, and rejects unknown
  input properties.
- Callers decide sensitivity from schema/policy metadata and pass sensitive values
  to `SensitiveValueMasker`. The default implementation returns `[REDACTED]`
  without leaking value length.

## Test utilities

`FixedPlatformClock` and `FixedUuidGenerator` provide deterministic time and IDs.
MockMvc contract tests should assert status, stable code, problem content type,
correlation headers, request ID, and absence of sensitive rejected values.

