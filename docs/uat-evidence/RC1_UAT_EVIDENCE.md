# RC-1 Prompt 68 UAT Evidence

This file is the durable text export of PostgreSQL runtime/audit evidence and UI
observations captured during the six-persona UAT on 2026-09-09. All timestamps
stored by the runtime are UTC; corresponding runbook execution times are shown
in `+07:00`.

## Employee happy path

```text
Ticket  7edc4a7a-5280-4fd9-96c5-1e017050be84
Creator 10000000-0000-4000-8000-000000000001 (Alice User)
Status  SUBMITTED
Revision f9b25112-d641-4c34-8f4b-edc74e6df805 / revisionNo 1
Event   91d6fe80-8f93-4f8f-8471-f8b7a8cf88a8
Version 68000000-0000-4000-8000-000000000002
Start job 992627a8-78dd-4ebd-80f8-fb675cb39028 / COMPLETED / attempts 1
```

## Manager and HR decisions

```text
Manager task 9e70f471-e50c-4c92-94d1-1fe789d6bfac
  status=COMPLETED outcome=APPROVED actor=20000000-0000-4000-8000-000000000002
  audit=f913c512-7c9e-4e18-9d6f-7655e7a6c2f5
  routing=b8c12cc4-2103-4ee9-96ed-97df48aadaaa
  selectedEdge=68000000-0000-4000-8000-000000000021

HR task d14384fc-36e1-4b5d-b343-77fb1635d5cb
  status=COMPLETED outcome=APPROVED actor=40000000-0000-4000-8000-000000000004
  audit=6c78d2cc-6110-49e6-85ce-77b738e28ec2
  routing=8d6d521f-2e1c-440b-a508-8d4348c23e87
  selectedEdge=68000000-0000-4000-8000-000000000024

Event 91d6fe80-8f93-4f8f-8471-f8b7a8cf88a8
  status=COMPLETED outcome=APPROVED
  node occurrence counts: start=1, manager_approval=1, hr_review=1, end_approved=1
```

## Manager reject branch

```text
Ticket b4e0e529-98ad-443a-9571-afc98e2e9325
Event  19a5f736-3fcf-4815-85d6-8c785df5b27a
Task   7045ae95-b1c8-4e9d-a144-3c4f038844de
Task status=COMPLETED outcome=REJECTED
Audit  7e15538e-af4b-43b9-bc63-bb83c82f6cf7
RoutingDecision 91d9aaae-b077-4030-b41f-cfa23396506d
Selected edge 68000000-0000-4000-8000-000000000022
Event status=COMPLETED outcome=REJECTED
Node occurrence counts: start=1, manager_approval=1, end_rejected=1
```

## Operator recovery

```text
Job 68000000-0000-4000-8000-000000000060
Before: DEAD, attempts=3/3, error=UAT_SIMULATED_WORKER_CRASH
Reason: UAT operator retry after simulated worker crash
Audit: 64eee7c5-060c-4fbe-8401-7dea34c73c63
Actor/principal: 30000000-0000-4000-8000-000000000003 (Charlie Ops)
Correlation: cac93706-ccbe-478e-8781-a12864743620
After: COMPLETED, attempts=4/4
Terminal Event after retry: COMPLETED / APPROVED; original ended_at preserved
```

## Authorization

```text
GET /api/v1/operations/failures
Alice User / USER: 403
Diana Admin / ADMIN: 200
```

## Workflow Owner UI observation

```text
Before: Version #1 DRAFT
Edit: Manager Approval -> Manager Approval RC-1
Save: Draft saved successfully
Validate: 0 Errors / 0 Warnings; graph passes compiler validation
Publish gate: 4 nodes / 3 transitions / all invariants passed
After: Version #1 PUBLISHED / Read-Only Canvas
Controls: Save Draft disabled; Publish disabled; node catalog disabled; properties disabled
```
