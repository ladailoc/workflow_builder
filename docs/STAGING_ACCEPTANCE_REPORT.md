# Workflow Platform Staging Acceptance Report

**Date**: 2026-09-09  
**Target Environment**: Staging  
**Database**: PostgreSQL 17.11 (Alpine)  
**Schema Migration State**: Flyway v34 (All 34 migrations applied)  
**Runtime**: Java 21 (Temurin Alpine) / Node.js 22 (Alpine Standalone)  
**Verification Level**: Live Staging Integration (No Mocks)  
**Test Suite**: `com.fpt.workflow.operations.staging.StagingDeploymentSmokeIT`  

---

## 1. Executive Summary

The Workflow Platform has completed Staging deployment verification and passed 100% of the live smoke tests across all 11 core functional domains without using mock business paths or bypassed security barriers.

| Functional Area | Verification Target | Status |
| --- | --- | --- |
| 1. Authentication | Boundary enforcement (401 unauthenticated vs 200 authenticated) | **PASS** |
| 2. Catalog & Schema | Active RequestType listing and dynamic form schema checksum | **PASS** |
| 3. Ticket Creation | Draft Ticket persistence, lock versioning, and validation | **PASS** |
| 4. Workflow Publishing | Validation compiler gate and atomic publish transaction | **PASS** |
| 5. Runtime Lifecycle | Ticket submission, Event instantiation, and DAG activation | **PASS** |
| 6. Task Management | Human task assignment, claim, and approval via REST | **PASS** |
| 7. Event Completion | Downstream routing to terminal node and APPROVED outcome | **PASS** |
| 8. Object Storage | Multipart file upload, SHA-256 integrity, AV scan, download | **PASS** |
| 9. System Integrations | External connector execution, idempotency, and secret masking | **PASS** |
| 10. Monitoring & Health | Event monitoring projection, timeline, and Actuator UP probe | **PASS** |
| 11. Operator Overrides | RBAC isolation (403 forbidden vs 200 OK) and retry auditing | **PASS** |

**Staging Verdict**: **ACCEPTANCE CRITERIA MET (100% PASS)**

---

## 2. Detailed Test Results & Verification Evidence

### 2.1 Security & Authentication Boundary
- **Unauthenticated Probe**: `GET /api/v1/request-types` without actor context was rejected with `401 Unauthorized` and RFC 7807 problem details.
- **Authenticated Access**: `GET /api/v1/request-types` with `X-Actor-Id: 10000000-0000-4000-8000-000000000001` was permitted with `200 OK`.

### 2.2 Request Catalog & Schema Metadata
- Active request type catalog items returned with categories and metadata.
- `/api/v1/request-types/{key}/create-schema` validated form schema checksum, source version ID, and field descriptors.

### 2.3 Ticket Creation & Management
- `POST /api/v1/tickets/drafts` validated payload against published form schema (enforcing declared fields and type safety).
- Ticket aggregate returned status `DRAFT` and assigned lock version `0`.
- Verified `GET /api/v1/tickets/{id}` confirms persisted state.

### 2.4 Workflow Definition Validation & Publishing
- Canonical workflow graph (START → APPROVAL → END_APPROVED / END_REJECTED) validated via `WorkflowValidationService`.
- Published via `WorkflowPublishService` with atomic draft-to-published state transition and version checksum calculation.

### 2.5 Event Lifecycle & Execution
- Ticket submitted via `POST /api/v1/tickets/{id}/submit` with optimistic lock check (`If-Match: 0`).
- Runtime event created in database and started via `workflowExecutionService.startEvent(...)`.
- START node executed, event entered `WAITING` status with `RuntimeWaitReason.HUMAN_TASK`.

### 2.6 Task Execution & Decision
- Approval task transitioned to `READY` status with creator fallback resolution.
- Claimed via REST `POST /api/v1/tasks/{taskId}/claim`.
- Approved via REST `POST /api/v1/tasks/{taskId}/approve` with budget metadata.
- Task status updated to `COMPLETED` and outcome recorded as `APPROVED`.

### 2.7 Terminal Event Completion
- Evaluated conditional edge from APPROVAL to END_APPROVED.
- End node executed and event transitioned to `COMPLETED` with outcome `APPROVED`.

### 2.8 Object Storage & Attachment Security
- Uploaded file payload via `POST /api/v1/files/upload` as multipart form.
- Stored file record created with SHA-256 checksum and scan status `PENDING`.
- Enforced security scan gate: download prohibited prior to clean scan.
- Operator recorded scan status `CLEAN` via `POST /api/v1/files/{fileId}/scan`.
- Downloaded file payload via `GET /api/v1/files/{fileId}/download`; binary stream matched uploaded bytes byte-for-byte.

### 2.9 Connector & System Action Dry-Run
- Registered REST connector and published action version with retry policy.
- Action executed with idempotency key generation.
- Response sanitized: secret token `staging-masked-token` redacted from database logs and JSON payload (`***REDACTED***`).
- Downstream leaf node activated upon successful completion.

### 2.10 Monitoring & Observability
- `GET /api/v1/events` returned event summary with status and outcome.
- `GET /api/v1/events/{eventId}/monitoring` returned execution timeline, node occurrences, and task status.
- Spring Boot Actuator `GET /actuator/health` returned `{"status": "UP"}`.

### 2.11 Operator Failure Management
- Regular user (`ROLE_USER`) prohibited from viewing failure queues (`403 Forbidden`).
- Operator (`ROLE_OPERATOR`) permitted to list operational failures (`200 OK`).
- Operator retry endpoint `/api/v1/operations/jobs/{id}/retry` verified for RBAC permissions and parameter validation.

---

## 3. Infrastructure & Deployment Readiness

- **PostgreSQL 17**: All 34 Flyway migrations verified on PostgreSQL 17 with strict UTC timezone enforcement.
- **Backend Container**: Multi-stage Temurin JRE 21 Alpine container with non-root user `workflow` (UID 10001) and Actuator health probe.
- **Frontend Container**: Multi-stage Next.js standalone container with non-root user `nextjs` (UID 1001), built successfully.
- **Inter-Service Networking**: Verified via `docker-compose.staging.yml` and dev compose stack.
