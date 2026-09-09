# Workflow Platform — Prompt 68 UAT Runbook

## Release candidate

| Field | Value |
| --- | --- |
| Release Candidate | `RC-1` |
| Git Commit | `31dadcd` |
| Environment | `local/staging` |
| Database | `PostgreSQL 17.11` |
| Flyway Version | `V34` |
| UAT Start Time | `2026-09-09 10:51:48 +07:00` |
| UAT End Time | `2026-09-09 11:35:15 +07:00` |
| Tester | `Dev` |

`31dadcd` is the re-baselined application RC after closing `UAT-BLOCK-01`. The
Prompt 68 fixture was loaded only into the local/staging database and is not
repository seed data for Prompt 69.

## Environment startup evidence

- PostgreSQL service: `workflow-platform-postgres-1`, PostgreSQL `17.11`, healthy.
- Backend: `http://localhost:8080`; startup verifier reported PostgreSQL major 17,
  session timezone UTC, and Flyway schema version 34.
- Frontend: `http://localhost:3000`.
- Authenticated UI identities used: Alice User (`USER`), Bob Owner
  (`WORKFLOW_OWNER`), Charlie Ops (`OPERATOR`), and Diana Admin (`ADMIN`). HR is
  a business participant, not a platform role; Diana was directly resolved as
  the HR task assignee.

## Six-persona UAT results

### 1. Employee

Status: `PASS`

Tester: `Dev` as Alice User (`10000000-0000-4000-8000-000000000001`, `USER`)

Executed at: `2026-09-09 11:15:12 +07:00`

Evidence:

- Selected Request Type `uat_rc1_request` from Request Catalog; no technical
  WorkflowVersion choice was exposed.
- Created and submitted Ticket
  `7edc4a7a-5280-4fd9-96c5-1e017050be84` with declared fields `title` and `amount`.
- Immutable revision:
  `f9b25112-d641-4c34-8f4b-edc74e6df805`, revision number `1`.
- Event: `91d6fe80-8f93-4f8f-8471-f8b7a8cf88a8`, exact bound version
  `68000000-0000-4000-8000-000000000002`.
- Durable start job: `992627a8-78dd-4ebd-80f8-fb675cb39028`, status
  `COMPLETED`, attempts `1`.
- Expected: submit creates an exact-version Event and activates the first human
  task once. Actual: Manager task `9e70f471-e50c-4c92-94d1-1fe789d6bfac`
  appeared automatically in `READY`; matched.

### 2. Manager

Status: `PASS`

Tester: `Dev` as Bob Owner (`20000000-0000-4000-8000-000000000002`,
`WORKFLOW_OWNER`) acting as the resolved Manager participant

Executed at: `2026-09-09 11:16:41 +07:00` and `11:19:49 +07:00`

Evidence:

- My Tasks showed only tasks visible to Bob, including Manager task
  `9e70f471-e50c-4c92-94d1-1fe789d6bfac`.
- Claimed and approved it with comment `UAT manager approval for RC-1`.
- Task lifecycle became `COMPLETED`; separate outcome became `APPROVED`.
- RoutingDecision `b8c12cc4-2103-4ee9-96ed-97df48aadaaa` selected edge
  `68000000-0000-4000-8000-000000000021` once and created HR task
  `d14384fc-36e1-4b5d-b343-77fb1635d5cb` once.
- Reject branch used Ticket `b4e0e529-98ad-443a-9571-afc98e2e9325`, Event
  `19a5f736-3fcf-4815-85d6-8c785df5b27a`, and Manager task
  `7045ae95-b1c8-4e9d-a144-3c4f038844de`.
- Rejected task lifecycle remained `COMPLETED`, outcome was `REJECTED`, and
  RoutingDecision `91d9aaae-b077-4030-b41f-cfa23396506d` selected rejected edge
  `68000000-0000-4000-8000-000000000022` exactly once. Event completed with
  outcome `REJECTED`; no `TaskStatus.REJECTED` was created.

### 3. HR

Status: `PASS`

Tester: `Dev` as Diana (`40000000-0000-4000-8000-000000000004`), directly
resolved as the HR business participant

Executed at: `2026-09-09 11:18:15 +07:00`

Evidence:

- My Tasks showed HR task `d14384fc-36e1-4b5d-b343-77fb1635d5cb`, assigned to
  the authenticated HR participant for Event
  `91d6fe80-8f93-4f8f-8471-f8b7a8cf88a8`.
- Claimed and approved with comment `UAT HR review accepted`.
- Task lifecycle became `COMPLETED`, outcome `APPROVED`; audit event
  `6c78d2cc-6110-49e6-85ce-77b738e28ec2` recorded the authenticated actor.
- RoutingDecision `8d6d521f-2e1c-440b-a508-8d4348c23e87` selected edge
  `68000000-0000-4000-8000-000000000024` once.
- Event completed `APPROVED`; occurrence counts were exactly one each for
  `start`, `manager_approval`, `hr_review`, and `end_approved`.

### 4. Workflow Owner

Status: `PASS`

Tester: `Dev` as Bob Owner (`WORKFLOW_OWNER`)

Executed at: `2026-09-09 11:20–11:22 +07:00`

Evidence:

- Opened version `#1 DRAFT` in Workflow Builder.
- Removed two properties rejected by the strict APPROVAL manifest, changed the
  node label to `Manager Approval RC-1`, and saved the draft.
- Validate returned `0 Errors / 0 Warnings` and `Graph passes compiler validation`.
- Publish gate reported all invariants passed for 4 nodes and 3 transitions.
- After confirmation the UI reported `Version #1 PUBLISHED`, `Read-Only Canvas`,
  disabled Save/Publish, disabled all node-add controls, and disabled selected
  node property inputs. Mutation attempts were therefore unavailable after
  publish; matched the Published immutability acceptance check.

### 5. Operator

Status: `PASS`

Tester: `Dev` as Charlie Ops (`30000000-0000-4000-8000-000000000003`,
`OPERATOR`)

Executed at: `2026-09-09 11:23:43 +07:00`

Evidence:

- Operations queue displayed DEAD job
  `68000000-0000-4000-8000-000000000060`, sanitized error
  `UAT_SIMULATED_WORKER_CRASH`, attempts `3/3`.
- Retried with mandatory reason `UAT operator retry after simulated worker crash`.
- Audit `64eee7c5-060c-4fbe-8401-7dea34c73c63` recorded event type
  `JOB_RETRY_REQUESTED`, Charlie's actor/principal, correlation ID
  `cac93706-ccbe-478e-8781-a12864743620`, and the exact reason.
- Worker reclaimed the job; final status `COMPLETED`, attempts `4/4`.
- The already-terminal Event remained `COMPLETED/APPROVED` with its original
  `ended_at`; no terminal execution was revived.
- Event monitoring displayed exact bound WorkflowVersion, tasks, routing
  decisions, occurrence activation keys, cycle `7eff575f-75ce-4e64-a45d-cdd479a475dd`,
  path tokens, and privileged context inspector.

### 6. Admin

Status: `PASS`

Tester: `Dev` as Diana Admin (`40000000-0000-4000-8000-000000000004`, `ADMIN`)

Executed at: `2026-09-09 11:25–11:26 +07:00`

Evidence:

- Admin identity opened Organization & Administration and had access to
  Workflow Builder and Operations navigation.
- Regular Alice User was switched onto the same Organization route and received
  `403 — Access Denied` in the UI.
- Backend source-of-truth authorization was checked directly against
  `GET /api/v1/operations/failures`: Alice `USER` returned HTTP `403`; Diana
  `ADMIN` returned HTTP `200`.

## Closed defect and retest

| Defect ID | Severity | Finding | Fix | Retest evidence | Status |
| --- | --- | --- | --- | --- | --- |
| `UAT-BLOCK-01` | High / Blocking | Ticket submit persisted Event but no deployed caller activated START; earlier integration fixtures called `WorkflowExecutionService.startEvent` directly | Submit now enqueues durable `EVENT_START` in the same transaction; an idempotent handler and profile-enabled leased worker process it after commit | Employee Ticket/Event/job/task IDs above; focused handler test PASS; full `mvn clean verify` PASS | `CLOSED` |

## Defect Register

Open Critical: `0`

Open High / Blocking: `0`

Open Medium: `0`

Open Low: `0`

Blocking defects: `NONE`

## Regression evidence

Backend verification after defect closure (`mvn clean verify`):

- Unit tests: `212` run, failures `0`, errors `0`, skipped `0`.
- PostgreSQL/Testcontainers integration tests: `158` run, failures `0`, errors
  `0`, skipped `0`.
- `BUILD SUCCESS`.
- Total time: `03:51`; finished at `2026-09-09T11:32:36+07:00`.
- Flyway clean installs reached `V34` on PostgreSQL `17.11` during the suite.

Frontend verification:

- Tests: `61/61 PASS` (`12/12` files).
- Lint: `PASS` (`eslint . --max-warnings=0`).
- Production Build: `PASS` (`next build`, all routes generated).

Detailed immutable identifiers and audit extracts are retained in
[`docs/uat-evidence/RC1_UAT_EVIDENCE.md`](uat-evidence/RC1_UAT_EVIDENCE.md).

## Sign-off

Overall UAT Result: `PASS`

Business Owner / UAT Approver: `Dev`

Decision: `ACCEPTED`

Date: `2026-09-09`

Open blocking defects: `0`

| Persona | Tester | Result | Signed at |
| --- | --- | --- | --- |
| Employee | Dev | PASS | 2026-09-09 11:35:15 +07:00 |
| Manager | Dev | PASS | 2026-09-09 11:35:15 +07:00 |
| HR | Dev | PASS | 2026-09-09 11:35:15 +07:00 |
| Workflow Owner | Dev | PASS | 2026-09-09 11:35:15 +07:00 |
| Operator | Dev | PASS | 2026-09-09 11:35:15 +07:00 |
| Admin | Dev | PASS | 2026-09-09 11:35:15 +07:00 |
| Business Owner / UAT Approver | Dev | ACCEPTED | 2026-09-09 11:35:15 +07:00 |

Prompt 69 was not started.
