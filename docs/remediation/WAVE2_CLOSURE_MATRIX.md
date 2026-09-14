# Wave 2 closure matrix

Source of truth: `workflow_spec.md` v2.3 and the master remediation prompt.

`FULL` below means the normative implementation and focused evidence exist. The Wave 2 gate is
reported separately because a wave cannot pass until the complete `mvn verify` run is green.

| Item | Status | Implementation evidence | Verification evidence |
| --- | --- | --- | --- |
| P1-01 | FULL | Central `VisibilityResolver`; persistence-aware implementation in the composition package; Ticket, Event monitoring, reporting, and file download use it | `DefaultVisibilityResolverTest`, `EventMonitoringServiceTest`, `TicketPersistenceIT` |
| P1-02 | FULL | `ParticipantResolutionResult` carries a user list, structured status, evidence, and detail without choosing an arbitrary user | `ParticipantResolutionEngineTest`, `ExpressionParticipantResolverTest` |
| P1-03 | FULL | `ParticipantResolutionEngine` evaluates only configured fallback chains and implements explicit `FAIL_NODE` / `CREATE_MANUAL_TASK` outcomes | `ParticipantResolutionEngineTest`, `HumanTaskParticipantActivationHookTest` |
| P1-04 | FULL | Registry-based resolver primitives for fixed user, manager, request field, role/group members, head of unit, previous participant, node output, expression, and external custom registrations | Participant resolver unit-test package and registry tests |
| P1-05 | FULL | `RequestFieldParticipantResolver` checks declared USER/USER_LIST type and directory identity/activity, rejects malformed or cross-scope IDs, and deduplicates | `RequestFieldParticipantResolverTest` |
| P1-06 | FULL | Typed `ticket_subjects` feed participant context; manager lookup uses selected subject rather than creator fallback | `ParticipantResolutionEngineTest.normalizedTicketSubjectsDriveManagerResolution` and missing/ambiguous subject cases |
| P1-07 | FULL | `InactiveAssigneeMonitor` detects directory changes, preserves assignment snapshots, emits deduplicated `ASSIGNEE_INACTIVE`, and exposes it operationally | `InactiveAssigneeMonitorTest`, `OperationalFailureServiceTest` |
| P1-08 | FULL | Human-task activation supports claimable pool, one task per user, and deterministic sequential tasks with candidates/aggregation state | `HumanTaskParticipantActivationHookTest`, `TaskAggregationDecisionTest`, `SequentialApprovalE2EIT` |
| P1-09 | FULL | SLA expire action calls the Task `EXPIRED` transition and writes `TASK_EXPIRED`; ordinary terminal-task commands remain guarded | `SlaActionExecutorTest.testSlaTimeoutAction_Expire_SetsTaskExpiredAndAudits`, task domain tests |
| P1-10 | FULL | FIRST/ANY joins implement `CANCEL_REMAINING` and `KEEP_RUNNING` with a single downstream decision under late/duplicate arrivals | `JoinIT`, including late and duplicate arrival cases |
| P1-11 | FULL | Validation requires valid `remainingItemPolicy` for threshold completion semantics | `WorkflowValidationCompilerIT.multiInstanceThresholdPolicy_withoutRemainingItemPolicy_failsValidation` and invalid/valid cases |
| P1-12 | FULL | Multi-instance scope binds configured `itemVariable`, current item, and stable item token/key for expressions and participant resolution | `HumanTaskParticipantActivationHookTest.testMultiInstance_BindsItemVariableAndItemContext_ForTaskCreation`, `MultiInstanceIT` |
| P1-13 | FULL | Task command API forwards REQUEST_REVISION through the idempotent command facade with mandatory command ID and expected version | `TaskCommandFacadeTest`, `LifecycleAndTaskCommandsApiIT` |
| P1-14 | FULL | Revision submission uses `RoutingService`, persisted routing decisions, activation tokens, and replay keys instead of direct activation | `RevisionRequestServiceTest`, `RoutingDecisionPersistenceIT.reworkRouting_createsRoutingDecision_incrementsIteration_andPreservesPathToken` |
| P1-15 | FULL | Rework policy enforces `maxIterations` and exhaustion action without double increment on replay | `ControlledReworkTest`, `RoutingDecisionPersistenceIT` exhaustion/replay cases |
| P1-16 | FULL | Protected cancel/restart/reopen/resubmit/unclaim/force-complete/resume endpoints use mandatory command/version headers; force-complete is privileged and requires a reason | `LifecycleAndTaskCommandsApiIT`, `TaskCandidateAuthorizationIT`, `TaskControllerIT` |
| P1-17 | FULL | All advertised compiler stages execute real participant, MI, join/token, SLA/calendar, version/revision, and cross-node checks | `WorkflowValidationCompilerIT.validGraphExecutesAllCompilerStages` plus stage-specific failures |
| P1-18 | FULL | Validation emits ERROR, WARNING, ACK_REQUIRED_WARNING, and INFO; publishing gates missing acknowledgements but not INFO | `WorkflowValidationCompilerIT`, workflow publish acknowledgement tests |
| P1-19 | FULL | Workflow validation endpoints return persisted structured run/issues under workflow authorization | Definition API/controller integration tests and `WorkflowValidationCompilerIT.persistsFreshRunAndIssues` |
| P1-20 | FULL | Definition analysis API delegates cross-node field usage discovery to `FieldDependencyAnalyzer` | Definition API tests with cross-node references |
| P1-21 | FULL | Connector configuration and the immediate outbound client path both invoke `ConnectorUrlSecurityValidator` | `ConnectorUrlSecurityValidatorTest`, `DefaultConnectorActionClientTest` |
| P1-22 | FULL | System action failures implement fail-node, fail-event, goto-node, fallback action, continue-with-warning, and manual-task strategies through lifecycle/routing services | Strategy cases in `SystemActionExecutionIT` |
| P1-23 | FULL | Integration success is `SUCCEEDED`, `externalRequestId` is persisted, and legacy `COMPLETED` data remains readable through migration V36 | `SystemActionExecutionIT.testSucceededAndExternalRequestId_persistsCorrectly` and backward-compatibility test |
| P1-24 | FULL | Sub-workflow activation/runtime failure obeys per-node failed-port or fail-parent policy without generic exception control flow | P1-24 cases in `SubWorkflowExecutionIT` |
| P1-25 | FULL | Explicit child execution scope blocks mutation of the shared parent Ticket unless an explicit permission scope is opened | P1-25 cases in `SubWorkflowExecutionIT` |
| P1-26 | FULL | Ticket submit/resubmit persist outbox records in the business transaction; leased scheduler publishes through a production application-event transport; no-op is explicit only | `DurableJobOutboxIT`, producer assertion in `TicketPersistenceIT` |
| P1-27 | FULL | Version-scoped, catalog-gated typed filtering uses fixed parameterized SQL, centralized visibility before stable pagination, bounded page size, overflow-safe offset, and focused indexes in V37 | `ReportableFieldCatalogTest`, `TicketReportingQueryServiceTest` |
| P1-28 | FULL | GitHub Actions uses Java 21, Maven cache, and fails on backend `mvn verify`; no repository secret is embedded | `.github/workflows/backend-verify.yml` |
| P1-29 | FULL | Staging compose requires DB/callback/JWT secrets; staging/prod startup rejects missing, short, wildcard, or known development credentials | `ProductionConfigurationValidatorTest`, `JwtAuthenticationTest` |

## Wave 2 exit gate

- Focused post-remediation suite: **PASS** (11 tests, including ArchUnit, visibility,
  reporting, monitoring, and task-command boundaries).
- Earlier full unit pass reached **335 tests** with one ArchUnit failure. That architectural failure
  was subsequently fixed and its test is green.
- Fresh Flyway migration through **V37**: **PASS** in PostgreSQL 17 integration tests.
- Final full `mvn verify`: **PENDING HOST RESOURCE RECOVERY**. Windows rejected native JVM
  allocation because the paging-file commit limit was exhausted; this is not recorded as a product
  PASS or FAIL.
- Wave 3 must not start until that last gate finishes green.
