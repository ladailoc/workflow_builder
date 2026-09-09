# Workflow Platform Observability, Metrics & Alerting Guide

This document defines the production observability, metrics catalog, alerting rules, and operational dashboard topology for the Workflow Platform.

---

## 1. Structured Logging & Context Correlation

The Workflow Platform enforces structured JSON logging with MDC context propagation across all thread boundaries and execution flows.

### Standard MDC Correlation Fields

| Key | Type | Source / Lifecycle | Example | Description |
|---|---|---|---|---|
| `requestId` | UUID / String | HTTP Filter / Worker Context | `550e8400-e29b-41d4-a716-446655440000` | Identifies single ingress HTTP request or job execution. |
| `correlationId` | UUID / String | Propagated or generated per flow | `corr-8823-9912` | End-to-end distributed trace tracking upstream caller. |
| `eventId` | UUID | Workflow Runtime Lifecycle | `b78a1c9e-32e1-4c4f-b673-1991d09e3a61` | Aggregate root event ID for running workflow instance. |
| `nodeExecutionId` | UUID | Node Activation Service | `94e4f8d2-55cf-4be3-b9dc-369f10925e0a` | Unique execution iteration for a node graph step. |
| `taskId` | UUID | Task Service / Human Task | `e0811e74-0f2c-4740-9a25-e51c8907b8cd` | Specific human task execution instance. |
| `commandId` | UUID | Command Handler Bus | `7c9e6679-7425-40de-944b-e07fc1f90ae7` | CQRS command identifier for audit & idempotency. |
| `integrationExecutionId` | UUID | Integration Service | `f47ac10b-58cc-4372-a567-0e02b2c3d479` | Asynchronous/synchronous external connector call ID. |

### Sensitive Data Scrubbing Rules
To adhere strictly to zero-trust security and data privacy policies:
1. **Never Log Secrets**: Passwords, bearer tokens, API keys, client secrets, private keys, authorization headers, and personal identifiable credentials MUST NEVER appear in plain text within log messages or MDC values.
2. **Automated Sanitization**: The platform leverages `SensitiveDataMasker` and `WorkflowMdcScope` to sanitize keys matching sensitive keywords (`password`, `secret`, `token`, `credential`, `authorization`, `apiKey`, `privateKey`) and redacts them as `[REDACTED]`.
3. **Payload Scrubbing**: Connector configurations and HTTP integration payloads redact `headers.Authorization` and encrypted credential properties before persisting or logging.

---

## 2. Micrometer Metrics Catalog

Authoritative persistence gauges and runtime meters exposed via Spring Boot Actuator (`/actuator/metrics` and Prometheus scrapers).

### Workflow Event Metrics
- `workflow.starts` (Gauge): Total workflow root events instantiated.
- `workflow.completions` (Gauge): Total workflow root events successfully completed.
- `workflow.failures` (Gauge): Total workflow root events marked in terminal `FAILED` status.

### Workflow Node Metrics
- `workflow.node.duration.seconds` (Gauge): Average execution duration across completed node executions.

### Task SLA & Lifecycle Metrics
- `workflow.task.sla.overdue` (Gauge): Total tasks past their configured SLA due date still in `READY`, `CLAIMED`, or `IN_PROGRESS` state.
- `workflow.task.duration.seconds` (Gauge): Average duration from task creation to completion.

### Routing & Synchronization Metrics
- `workflow.routing.failures` (Gauge): Count of routing decision failures (`ROUTING_FAILED` audit events) requiring operator intervention.
- `workflow.join.waiting` (Gauge): Active parallel branch join points awaiting sibling completions.
- `workflow.join.completion` (Gauge): Join points successfully synchronized and evaluated.

### Integration & Connector Metrics
- `workflow.integration.calls` (Gauge): Total integration executions initiated.
- `workflow.integration.latency.seconds` (Gauge): Average latency for outbound connector actions.
- `workflow.integration.failures` (Gauge): Count of failed integration executions and those requiring manual reconciliation.
- `workflow.integration.retries` (Gauge): Cumulative count of retry attempts (attempt number > 1).

### Callback Invariant Metrics
- `workflow.callback.late` (Gauge): Async callbacks received after the associated node execution or event had already terminated.
- `workflow.callback.duplicate` (Gauge): Callbacks received with duplicate idempotency tokens.

### Durable Outbox & Background Worker Metrics
- `workflow.jobs.ready` (Gauge): Outbox jobs pending immediate dispatch.
- `workflow.jobs.running` (Gauge): Outbox jobs currently leased by worker threads.
- `workflow.jobs.retry` (Gauge): Outbox jobs awaiting exponential backoff retry.
- `workflow.jobs.dead` (Gauge): Outbox jobs permanently dead-lettered after exhausting retries.
- `workflow.jobs.lease.stalled` (Gauge): Leased jobs whose lease expired before heartbeat completion.
- `workflow.worker.stalled` (Gauge): Severe worker stall (lease expired by > 2 minutes), indicating deadlocked worker or ungraceful termination.

---

## 3. Health & Readiness Probes

Spring Boot Actuator exposes health status at `/actuator/health` with dedicated Kubernetes probe groups:

- **Liveness Probe**: `/actuator/health/liveness`
  - Validates process responsiveness (`ping`).
- **Readiness Probe**: `/actuator/health/readiness`
  - Validates platform readiness:
    - PostgreSQL connection check (`SELECT 1`).
    - `WorkflowPlatformHealthIndicator`:
      - Validates database accessibility.
      - Monitors outbox DEAD job count.
      - Monitors stalled worker lease count.
      - Monitors failed integration count.

---

## 4. Production Prometheus Alert Definitions

```yaml
groups:
  - name: workflow_platform_alerts
    rules:

      # 1. High Error Rate
      - alert: WorkflowHighErrorRate
        expr: (rate(workflow_failures[5m]) / (rate(workflow_starts[5m]) + 0.001)) > 0.05
        for: 2m
        labels:
          severity: critical
          component: workflow-engine
        annotations:
          summary: "Workflow event failure rate exceeds 5%"
          description: "Workflow event failure rate is currently {{ $value | humanizePercentage }} over the last 5 minutes."
          runbook_url: "https://wiki.fpt.com/ops/runbooks/workflow-high-error-rate"

      # 2. DEAD Jobs Detected
      - alert: WorkflowDeadJobsDetected
        expr: workflow_jobs_dead > 0
        for: 1m
        labels:
          severity: critical
          component: outbox-worker
        annotations:
          summary: "Durable outbox jobs permanently dead-lettered"
          description: "{{ $value }} durable jobs have exceeded maximum retry attempts and moved to DEAD state."
          runbook_url: "https://wiki.fpt.com/ops/runbooks/dead-jobs-reconciliation"

      # 3. Database Connectivity Failure
      - alert: WorkflowDatabaseDown
        expr: up{job="workflow-platform"} == 0
        for: 30s
        labels:
          severity: critical
          component: database
        annotations:
          summary: "Workflow Platform backend service or database unreachable"
          description: "Workflow Platform backend instance failed liveness/readiness probes or database connection pool is exhausted."
          runbook_url: "https://wiki.fpt.com/ops/runbooks/database-outage"

      # 4. Integration Failures Spike
      - alert: WorkflowIntegrationFailuresSpike
        expr: workflow_integration_failures > 5
        for: 3m
        labels:
          severity: warning
          component: connectors
        annotations:
          summary: "Elevated connector integration failures"
          description: "{{ $value }} integration executions are in FAILED or MANUAL_RECONCILIATION status."
          runbook_url: "https://wiki.fpt.com/ops/runbooks/integration-reconciliation"

      # 5. Worker Stalled
      - alert: WorkflowWorkerStalled
        expr: workflow_worker_stalled > 0
        for: 2m
        labels:
          severity: critical
          component: outbox-worker
        annotations:
          summary: "Worker job lease stalled without heartbeat"
          description: "{{ $value }} jobs have leases expired by more than 2 minutes, indicating hung workers or aborted threads."
          runbook_url: "https://wiki.fpt.com/ops/runbooks/stalled-worker-recovery"

      # 6. Large Retry Backlog
      - alert: WorkflowRetryBacklogHigh
        expr: workflow_jobs_retry > 50 or workflow_jobs_ready > 200
        for: 5m
        labels:
          severity: warning
          component: outbox-queue
        annotations:
          summary: "Outbox job backlog or retry queue elevated"
          description: "Backlog indicates {{ $value }} jobs pending retry or execution, exceeding processing capacity."
          runbook_url: "https://wiki.fpt.com/ops/runbooks/queue-backlog"
```

---

## 5. Operational Dashboard Layout (Grafana)

### Dashboard 1: Workflow Platform Executive & SLA Overview
- **Row 1: Key Performance Indicators**
  - Singlestat: Event Starts / Hour (`rate(workflow_starts[1h])`)
  - Singlestat: Success Ratio (`rate(workflow_completions[1h]) / rate(workflow_starts[1h])`)
  - Singlestat: Overdue Tasks (`workflow_task_sla_overdue`)
  - Singlestat: Active Dead Jobs (`workflow_jobs_dead`)
- **Row 2: Workflow Throughput & Latency**
  - Graph: Starts, Completions, Failures over time (5m sliding window)
  - Heatmap / Quantiles: Node execution duration (`workflow_node_duration_seconds`)
  - Bar Gauge: Task completion duration average (`workflow_task_duration_seconds`)

### Dashboard 2: Outbox & Asynchronous Worker Operations
- **Row 1: Job Queue States**
  - Stacked Graph: `workflow_jobs_ready`, `workflow_jobs_running`, `workflow_jobs_retry`, `workflow_jobs_dead`
  - Alert Threshold Line: DEAD jobs > 0 (Red)
- **Row 2: Worker Health & Leases**
  - Graph: `workflow_jobs_lease_stalled` vs `workflow_worker_stalled`
  - Table: Top failing job types and retry counts

### Dashboard 3: Connectors & External Integrations
- **Row 1: Connector Execution & Latency**
  - Graph: Integration calls throughput (`rate(workflow_integration_calls[1m])`)
  - Graph: Outbound response latency (`workflow_integration_latency_seconds`)
- **Row 2: Reliability & Callback Auditing**
  - Graph: Integration Failures vs Retries
  - Singlestat / Counter: Late callbacks (`workflow_callback_late`) & duplicate callbacks (`workflow_callback_duplicate`)
  - Table: Pending manual reconciliations from `/api/v1/operations/failures`

---

## 6. Operational Triage Playbooks

### Incident 1: `WorkflowDeadJobsDetected`
1. Navigate to Operator Console (`/operations`).
2. Query `/api/v1/operations/failures` to inspect dead jobs and stack traces.
3. Review underlying root cause (schema mismatch, external API breaking change).
4. Apply fix and invoke retry endpoint `/api/v1/operations/failures/{id}/retry`.

### Incident 2: `WorkflowWorkerStalled`
1. Check backend worker node CPU/memory saturation via infrastructure metrics.
2. Inspect application logs filtering by `eventId` or `commandId`.
3. The platform's durable outbox lease manager will automatically reclaim expired leases after the timeout threshold; verify lease reclamation via `workflow_jobs_lease_stalled` returning to 0.

### Incident 3: `WorkflowHighErrorRate`
1. Check Grafana Event Throughput panel for sudden spike in `workflow_failures`.
2. Filter structured logs by `status=FAILED` and extract sample `eventId`.
3. Trace node execution history in `/events/{eventId}` to pinpoint failing node definition or script step.
