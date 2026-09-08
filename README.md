# Workflow Platform

Greenfield modular monolith for the Workflow Platform described by
`workflow_spec.md`. The implemented slice currently covers platform conventions,
security foundations, canonical lifecycle primitives, PostgreSQL infrastructure,
request/workflow definition persistence, and design-time workflow graph
persistence. Runtime workflow execution and business workflow capabilities are
intentionally not implemented yet.

## Prerequisites

- Java 21
- Maven 3.6.3+
- Node.js 20.9+
- npm 10+
- Docker with Docker Compose

All runtime persistence timestamps use UTC. Local processes also run with UTC as
their default timezone. Business-calendar and presentation timezones will remain
explicit configuration in later capabilities.

## Start PostgreSQL

Copy `.env.example` to `.env`, replace the local password, then run:

```bash
docker compose --env-file .env -f infra/compose.yaml up -d
```

PostgreSQL 17 is exposed on `${POSTGRES_PORT:-5432}`. The Compose health check can
be inspected with `docker compose --env-file .env -f infra/compose.yaml ps`.
The server and every pooled JDBC session are forced to UTC. Database files live
in the named `workflow-postgres-data` volume.

## Backend

```bash
cd backend
mvn test
mvn spring-boot:run
```

The backend listens on `http://localhost:8080`. Future REST controllers must be
mounted below `/api/v1`; actuator or infrastructure endpoints must not be placed
under the business API prefix.

The default `dev` profile uses PostgreSQL and HikariCP settings from
`.env.example`, falling back to the same safe local defaults as Docker Compose.
Startup fails if the application connects to a PostgreSQL major version other
than 17 or if a pooled session is not using UTC. Flyway owns schema changes;
Hibernate is configured with `ddl-auto=validate` and never creates tables.

Useful quality commands:

```bash
mvn spotless:check
mvn test
mvn verify
```

`mvn verify` runs the `PostgresqlStartupIT` smoke test against an ephemeral
`postgres:17-alpine` Testcontainer. Docker must be running; H2 is not used.

Backend tests use these conventions:

- `*Test` for fast unit and architecture tests executed by Surefire.
- `*IT` for integration and concurrency tests executed by Failsafe.
- PostgreSQL integration tests use Testcontainers rather than an in-memory SQL
  substitute.
- Tests and Maven forks run with `user.timezone=UTC`.

## Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend is available at `http://localhost:3000`.

Useful quality commands:

```bash
npm run format:check
npm run lint
npm run typecheck
npm run build
```

Frontend tests added by later capabilities should be colocated with the feature
as `*.test.ts` or `*.test.tsx`. End-to-end tests should live in `frontend/e2e`.

## Repository layout

See [`docs/architecture.md`](docs/architecture.md) for module boundaries,
dependency rules, UUID conventions, and the expanded project tree.
Shared REST, error, JSON, transaction, clock, UUID, masking, and test conventions
are documented in [`docs/backend-conventions.md`](docs/backend-conventions.md).
Authentication, authorization, audit-principal, and frontend session boundaries
are documented in [`docs/security-foundation.md`](docs/security-foundation.md).
Canonical IDs, versions, lifecycle/outcome, transition guards, and pagination
are documented in [`docs/domain-primitives.md`](docs/domain-primitives.md).
Request Type, Workflow Definition, and Workflow Version persistence is
documented in
[`docs/definition-persistence.md`](docs/definition-persistence.md).
Design-time node and edge persistence is documented in
[`docs/workflow-graph-persistence.md`](docs/workflow-graph-persistence.md).
