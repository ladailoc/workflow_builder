# Bootstrap architecture

## Decisions

The backend is a single Spring Boot deployable organized as a package-modular
monolith. This keeps the first implementation operationally simple while making
the specification's domain boundaries explicit. A package must expose deliberate
public contracts before another package depends on it; package internals should
remain package-private. Feature-to-feature cycles are rejected by an ArchUnit
test. `shared` may contain technical primitives only and must not become a home
for workflow business logic.

Shared backend contracts are described in `docs/backend-conventions.md`.
Canonical UUID, version, lifecycle, outcome, transition, and pagination values
are described in `docs/domain-primitives.md`.
The first P0 catalog/definition schema and its restrictive delete/immutability
rules are described in `docs/definition-persistence.md`.

The frontend uses the Next.js App Router and feature-first boundaries. Route files
compose feature exports; runtime business decisions remain server-side. Shared API
code owns transport concerns and uses `/api/v1` as the only business API prefix.
TanStack Query is initialized once in the app shell. React Flow is installed for
the future workflow builder but no graph semantics are implemented in this phase.

PostgreSQL is the only local infrastructure service currently defined. Object
storage, queues, workers, and observability services are intentionally deferred
until a capability needs them.

PostgreSQL 17 is also the integration-test database through Testcontainers.
HikariCP initializes every JDBC connection with `SET TIME ZONE 'UTC'`; the
container starts PostgreSQL with both `timezone` and `log_timezone` set to UTC.
Flyway owns schema evolution, while Hibernate runs only in validation mode. The
initial migration verifies PostgreSQL 17 and deliberately creates no domain
table or extension.

## Backend packages

| Package | Responsibility reserved by the specification |
| --- | --- |
| `workflow.definition` | Definition/version lifecycle, validation, package build, diff |
| `workflow.runtime` | Event runtime, activation, routing, joins, multi-instance, commands |
| `workflow.nodetype` | Node type registry, providers, handlers, validators |
| `workflow.task` | Human tasks, authorization, aggregation, assignment history |
| `workflow.form` | Form schemas, ticket validation, revision requests |
| `workflow.organization` | Organization hierarchy, closure maintenance and validation |
| `workflow.resolver` | Participant, variable, expression and organization resolution |
| `workflow.integration` | Connector registry, execution, retry and callback correlation |
| `workflow.subworkflow` | Parent/child event orchestration |
| `workflow.slanotification` | SLA, business calendars and notifications |
| `workflow.operations` | Jobs, outbox, recovery, audit and monitoring |
| `workflow.security` | Authentication, authorization and data masking policies |
| `workflow.shared` | Technical conventions and narrowly shared primitives only |

## Frontend boundaries

- `src/app`: app shell, providers, layouts, and route composition.
- `src/features/request-catalog`: request discovery and creation entry points.
- `src/features/tickets`: ticket views and commands.
- `src/features/events`: runtime event views.
- `src/features/tasks`: task inbox and task interaction.
- `src/features/workflow-builder`: design-time builder UI.
- `src/features/organization-admin`: organization and administration UI.
- `src/features/operations`: monitoring and recovery UI.
- `src/features/auth`: session state and UX-only route/permission guards.
- `src/shared/api`: HTTP transport only.
- `src/shared/types`: transport-neutral shared TypeScript types.
- `src/shared/components`: presentation-only reusable components.

## Cross-cutting conventions

- Persist instants as PostgreSQL `TIMESTAMPTZ` and Java `Instant`, normalized to
  UTC. Never persist an ambiguous local datetime for runtime state.
- New aggregate/runtime identifiers use PostgreSQL `uuid` and Java `UUID`.
  Application-generated IDs use RFC 4122 random UUIDs until an ADR intentionally
  adopts another UUID generation strategy.
- Business APIs live below `/api/v1`. Generic status mutation endpoints are not
  permitted; future state changes use explicit commands.
- Backend formatting is enforced by Spotless/Google Java Format. Frontend
  formatting uses Prettier with the Tailwind plugin; linting uses the Next.js
  TypeScript rules.
- `.env` is local and ignored. `.env.example` contains names and safe placeholders
  only; secrets never enter workflow configuration or source control.
- Actor identity is derived only from Spring Security's authenticated principal.
  Backend authorization is authoritative; frontend guards are UX affordances.

## Project tree

```text
.
├── backend
│   ├── pom.xml
│   └── src
│       ├── main
│       │   ├── java/com/fpt/workflow
│       │   │   ├── definition
│       │   │   ├── form
│       │   │   ├── integration
│       │   │   ├── nodetype
│       │   │   ├── operations
│       │   │   ├── organization
│       │   │   ├── resolver
│       │   │   ├── runtime
│       │   │   ├── security
│       │   │   ├── shared
│       │   │   ├── slanotification
│       │   │   ├── subworkflow
│       │   │   └── task
│       │   └── resources/application.yaml
│       └── test/java/com/fpt/workflow
├── frontend
│   ├── src/app
│   ├── src/features
│   │   ├── events
│   │   ├── operations
│   │   ├── organization-admin
│   │   ├── request-catalog
│   │   ├── tasks
│   │   ├── tickets
│   │   └── workflow-builder
│   └── src/shared
│       ├── api
│       ├── components
│       └── types
├── infra/compose.yaml
└── docs/architecture.md
```
