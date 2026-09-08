**WORKFLOW PLATFORM**

**TÀI LIỆU ĐẶC TẢ HỆ THỐNG & KIẾN TRÚC RUNTIME**

FINAL FULL EXPANDED SPECIFICATION v2.3

Workflow Builder • Request Type • Ticket • Event Runtime • Node Configuration • Dynamic Form • Organization Hierarchy • Participant Engine • Routing • Validation • Multi-instance • Sub-workflow • Integration • Reliability

|  |
| --- |
| TRẠNG THÁI: Bản FULL EXPANDED. Giữ nguyên toàn bộ quyết định cuối của v2.1 và bảo lưu đầy đủ các chi tiết kỹ thuật, ví dụ, bảng, rule và checklist của v2.0 để không bị rút gọn. Phần v2.3/v2.1 ở thân chính là semantics ưu tiên khi có khác biệt. |

|  |  |
| --- | --- |
| **Phiên bản** | 2.2 – Full Expanded Consolidated Baseline |
| **Ngày chốt** | 03/09/2026 |
| **Thay thế** | Workflow\_Platform\_Final\_Detailed\_Specification\_v2.2\_FULL\_EXPANDED |
| **Mục tiêu** | Đủ rõ để chuyển trực tiếp sang physical ERD, PostgreSQL schema/Flyway migrations, Java runtime, REST APIs, React Flow Builder và integration/concurrency tests |
| **Quy tắc thay đổi** | Thay đổi semantics nền tảng hoặc physical DB invariant sau v2.3 phải được ghi nhận bằng ADR / decision record mới |

# 0. Thông tin tài liệu, phạm vi và quyết định nền tảng

LƯU Ý VỀ TÍNH ĐẦY ĐỦ: Phần chính của tài liệu giữ toàn bộ quyết định cuối của v2.1. Phụ lục E bảo lưu đầy đủ chi tiết triển khai từ baseline v2.0 (không lược bỏ), bao gồm các ví dụ, bảng, command/state rule, API, test, roadmap và checklist. Nếu có khác biệt semantics, quyết định ở phần chính v2.3/v2.1 có hiệu lực ưu tiên.

## 0.1 Mục đích

Tài liệu này mô tả kiến trúc đích cho một Workflow Platform nội bộ có khả năng định nghĩa, publish, thực thi, theo dõi và mở rộng quy trình nghiệp vụ mà không hard-code theo từng workflow. Bản v2.1 tập trung làm rõ các boundary thường gây lỗi khi triển khai thực tế: cấu hình node, cách chọn node tiếp theo, form đầu vào của Ticket, yêu cầu bổ sung thông tin, validation/publish gate, hierarchy nhân sự, concurrency, integration và recovery.

## 0.2 Các invariant đã chốt

* Workflow được định nghĩa bằng configuration/graph; engine không chứa if/else theo tên workflow cụ thể.
* WorkflowDefinition/WorkflowVersion là design-time; Ticket/Event/NodeExecution/TaskExecution là runtime/business-time.
* Published WorkflowVersion là immutable execution package; Event bind một version tại thời điểm start và không tự chuyển version.
* Ticket là business request/case; Event là một lần chạy WorkflowVersion cho Ticket.
* NodeDefinition là cấu hình; NodeExecution là occurrence runtime. Một NodeDefinition có thể có nhiều execution do rework, loop hoặc multi-instance.
* NodeHandler không chọn node tiếp theo. EdgeDefinition + RoutingService là source of truth duy nhất cho routing.
* Participant được resolve tại activation và snapshot. Organization thay đổi không âm thầm đổi task đang active.
* Ticket Creator không được thêm field tùy ý. Chỉ Workflow Owner/Editor thay đổi Workflow Form Schema trên Draft.
* Reviewer/Approver có thể REQUEST\_REVISION và yêu cầu Runtime Requested Fields cho riêng Ticket/revision; chúng không làm thay đổi WorkflowVersion.
* Runtime sử dụng at-least-once + idempotency; không giả định exactly-once.
* Mọi state-changing operation là command có authorization, state guard, expected version/revision, idempotency và audit.
* Không external call trong transaction dài; durable job/outbox được dùng khi bước thực thi cần recovery.
* Core không trở thành arbitrary-script/BPMN-complete/low-code platform. Extension phải đi qua NodeType/Resolver/Connector/Policy registry có kiểm soát.

## 0.3 Phạm vi không làm trong core

* Không cho Workflow Owner chạy arbitrary JavaScript, Java, shell, raw SQL hoặc arbitrary HTTP URL.
* Không hỗ trợ hot-migrate âm thầm Event đang chạy sang WorkflowVersion mới.
* Không dùng event sourcing/CQRS chỉ để tăng độ phức tạp nếu relational state + append-only audit đã đủ.
* Database baseline dùng PostgreSQL theo mô hình Hybrid Relational + JSONB: identity/state/relationship/correlation lưu relational; dynamic workflow configuration, typed form schema và runtime snapshots lưu JSONB có kiểm soát.
* Không ép triển khai đầy đủ BPMN semantics; chỉ giữ primitive cần thiết cho nghiệp vụ nội bộ.
* Không đưa multi-tenancy vào core nếu sản phẩm chỉ phục vụ một tổ chức.

## 0.4 Thuật ngữ chính

|  |  |
| --- | --- |
| **Thuật ngữ** | **Định nghĩa chốt** |
| Request Type | Loại yêu cầu nghiệp vụ user chọn khi tạo Ticket; map tới WorkflowDefinition. |
| Ticket | Business request/case và dữ liệu nghiệp vụ do user tạo. |
| Event | Runtime instance chạy một WorkflowVersion cho Ticket. |
| WorkflowDefinition | Identity/capability của một workflow qua nhiều version. |
| WorkflowVersion | Snapshot cấu hình versioned, Draft hoặc Published/Superseded. |
| NodeDefinition | Bước design-time trong graph. |
| NodeExecution | Occurrence runtime của NodeDefinition. |
| TaskExecution | Human task cụ thể thuộc NodeExecution. |
| Participant | Người/nhóm có vai trò xử lý runtime. |
| Business Subject | Đối tượng nghiệp vụ mà process tác động/đánh giá; khác assignee. |
| Runtime Requested Field | Field phát sinh khi REQUEST\_REVISION, chỉ áp dụng Ticket/Event/revision hiện tại. |
| Organization Unit | Đơn vị tổ chức: company/division/department/team. |
| Position | Chức danh/vị trí trong reporting hierarchy. |

# MỤC LỤC NỘI DUNG

1. Tầm nhìn và kiến trúc tổng thể

2. Domain model và boundary Definition–Ticket–Event

3. Request Catalog, Ticket Creation và Workflow Selection

4. WorkflowVersion và Immutable Execution Package

5. Node Configuration Contract và NodeType Registry

6. Dynamic Form Engine, Ticket Form và Runtime Requested Fields

7. EventContext, Canonical Type System, Mapping và Expression

8. Organization & Personnel Hierarchy

9. Dynamic Participant Resolution

10. Human Task Runtime, Assignment và Decision Aggregation

11. Multi-instance / Dynamic Fan-out

12. Routing & Next-node Selection – Runtime Algorithm

13. Graph Semantics: Condition, Parallel Split/Join và Tokens

14. Rework, Request Revision và Controlled Cycles

15. SLA, Business Calendar, Escalation và Notification

16. Dynamic Sub-workflow

17. System Action / Integration / Callback / Compensation

18. File & Attachment Model

19. Ticket & Event Lifecycle

20. Commands, Authorization và Visibility

21. Concurrency, Idempotency, Race và Crash Recovery

22. Validation Engine, Publish Gate và Simulation

23. Publish/Version/Change Management, Diff và Rollback

24. Monitoring, Audit, Operational Recovery và Outbox

25. Mô hình dữ liệu vật lý & Database Design chốt

26. API Contract khuyến nghị

27. Backend module/component architecture

28. Frontend Workflow Builder & Runtime UI

29. Security, Privacy, Retention và Data Handling

30. Test Strategy & Acceptance Criteria

31. Roadmap triển khai

32. Anti-pattern và implementation rules

33. Checklist & Definition of Done

Phụ lục A–E. JSON mẫu, state matrix, runtime pseudocode, decision log và toàn bộ chi tiết kỹ thuật bảo lưu từ v2.0

# 1. Tầm nhìn và kiến trúc tổng thể

## 1.1 Definition-driven execution

Runtime engine chỉ hiểu các primitive generic: node type, edge, port, condition, participant resolver, form schema, mapping, completion policy, integration action và state machine. Workflow mới được tạo bằng cấu hình các primitive này, không bằng code branch riêng theo tên quy trình.

|  |
| --- |
| WorkflowDefinition  └─ WorkflowVersion  ├─ TicketFormSchema  ├─ TriggerDefinition  ├─ VariableDefinition[]  ├─ NodeDefinition[]  ├─ EdgeDefinition[]  ├─ ParticipantRule[]  └─ Policies / Metadata  Ticket  └─ Event  ├─ EventContext  ├─ NodeExecution[]  │ └─ TaskExecution[]  ├─ ParticipantSnapshot[]  ├─ IntegrationExecution[]  ├─ SLAExecution[]  └─ AuditEvent[] |

## 1.2 Bốn lớp dynamic

|  |  |
| --- | --- |
| **Lớp** | **Khả năng động** |
| L1 – Graph | Node, edge, branch, condition, parallel, join, controlled rework. |
| L2 – Business | Participant, form, SLA, approval policy, notification, request fields. |
| L3 – Runtime | Input/output, variables, fan-out, wait/resume, retry, callback. |
| L4 – Integration | Connector actions, webhook/domain event, sub-workflow, external systems. |

## 1.3 Registry architecture

|  |
| --- |
| NodeTypeRegistry ParticipantResolverRegistry ConnectorRegistry  │ │ │  NodeTypeProvider ResolverProvider ConnectorProvider  │ │ │  NodeHandler OrganizationResolver ActionHandler |

|  |
| --- |
| **QUYẾT ĐỊNH CHỐT:** Thêm capability mới phải đăng ký vào registry và có schema/validator/handler. Không sửa core bằng switch lớn theo workflow hoặc business name. |

# 2. Domain model và boundary Definition–Ticket–Event

## 2.1 Design-time aggregate

|  |
| --- |
| WorkflowDefinition  id, key, name, lifecycle  currentPublishedVersionId  activeDraftVersionId?  WorkflowVersion  id, definitionId, versionNumber  status = DRAFT | PUBLISHED | SUPERSEDED | ARCHIVED  revision / checksum  executionPackageSnapshot |

## 2.2 Runtime aggregate

|  |
| --- |
| Ticket  id, requestType, creatorId, status, dataJson, dataRevision  Event  id, ticketId, workflowVersionId  status, outcome, rootEventId, parentEventId?  NodeExecution  id, eventId, nodeDefinitionId  cycleId, iteration, pathToken, itemToken?  status, outcomePort, inputSnapshot, outputSnapshot  TaskExecution  id, nodeExecutionId  assignee/candidates, status, outcome, dueAt |

## 2.3 NodeDefinition ≠ NodeExecution

Không đặt unique(eventId, nodeDefinitionId). Rework hoặc loop tạo occurrence mới. Multi-instance có correlation item riêng. History không bị rewrite.

|  |
| --- |
| NodeDefinition: MANAGER\_REVIEW  Event E100  NodeExecution #12 cycle=1 COMPLETED  NodeExecution #25 cycle=2 RUNNING |

## 2.4 Technical state khác business outcome

|  |  |  |
| --- | --- | --- |
| **Object** | **Lifecycle state** | **Business outcome** |
| Event | CREATED, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED, TERMINATED | APPROVED, REJECTED, SUCCESS, WITHDRAWN, domain-defined |
| NodeExecution | CREATED, READY, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED, SKIPPED | Port/outcome: APPROVED, REJECTED, SUCCESS, ERROR... |
| TaskExecution | READY, CLAIMED, IN\_PROGRESS, COMPLETED, CANCELLED, EXPIRED | APPROVED, REJECTED, RETURNED, SUBMITTED... |

|  |
| --- |
| **QUYẾT ĐỊNH CHỐT:** Reject task không phải TaskStatus.REJECTED. Task hoàn thành với outcome=REJECTED; node aggregation tạo outcomePort=REJECTED rồi RoutingService xử lý. |

# 3. Request Catalog, Ticket Creation và Workflow Selection

## 3.1 End user chọn Request Type, không chọn workflow kỹ thuật

|  |
| --- |
| Create Ticket  ↓  Choose Request Type  ↓  Resolve active WorkflowDefinition  ↓  Load current Published TicketFormSchema  ↓  Render Dynamic Form  ↓  Submit  ↓  Resolve current Published version again  ↓  Validate ticket data  ↓  Create Ticket + Event + bind version |

UI hiển thị business capability như Nghỉ phép, Mua hàng, Cấp quyền, Đánh giá nhân viên. Workflow name/version chỉ dành cho Owner/Admin/Operator.

## 3.2 RequestType model P0

|  |  |
| --- | --- |
| **Field** | **Ý nghĩa** |
| key | Stable business key, ví dụ PURCHASE\_REQUEST. |
| name | Tên hiển thị. |
| category | Nhóm nghiệp vụ. |
| active | Có cho tạo mới hay không. |
| creationPolicy | Ai được tạo. |
| workflowDefinitionId | WorkflowDefinition active cho Request Type trong P0. |

|  |
| --- |
| **SCOPE CHỐT:** P0: một Request Type map tới một active WorkflowDefinition. Khi thật sự cần rule theo amount/department mới mở rộng routing ở tầng Request Type. |

## 3.3 Ticket Form là Workflow Input Contract

TicketFormSchema của current Published WorkflowVersion quyết định các field user phải nhập. Backend dùng cùng schema để validate; frontend chỉ render/validate sớm cho UX.

## 3.4 Version đổi trong lúc user đang nhập Ticket

1. Khi mở form, client nhận sourceWorkflowVersionId + formSchemaVersion/checksum.

2. Ticket draft chưa bind Event version.

3. Khi Submit, backend resolve current Published WorkflowVersion lại.

4. Nếu schema mới tương thích, validate data theo version mới và bind version mới.

5. Nếu version mới thêm required field, trả FORM\_SCHEMA\_CHANGED/TICKET\_SCHEMA\_OUTDATED và yêu cầu user bổ sung; chưa tạo Event.

6. Chỉ sau khi Ticket data hợp lệ mới tạo Event và bind version.

|  |
| --- |
| **QUYẾT ĐỊNH CHỐT:** Event creation/start là version binding point. Mở form hoặc lưu Ticket Draft không pin workflow cũ. |

# 4. WorkflowVersion và Immutable Execution Package

## 4.1 Những gì phải freeze/version khi publish

|  |  |
| --- | --- |
| **Thành phần** | **Publish behavior** |
| Trigger/config | Snapshot/versioned. |
| TicketFormSchema | Snapshot immutable. |
| NodeDefinition + configSchemaVersion | Snapshot immutable. |
| Edge/condition | Snapshot immutable. |
| Task form schemas | Snapshot immutable. |
| Participant rules | Snapshot immutable; actual users resolve runtime. |
| Multi-instance / completion policies | Snapshot immutable. |
| SLA / notification template/config | Effective values snapshot. |
| Connector action | Pin connectorKey + actionKey + actionVersion. |
| Sub-workflow | Snapshot child definition reference + resolution policy; child exact version resolve at activation by default. |
| Platform defaults | Resolve effective defaults at publish and snapshot semantics. |

## 4.2 Những gì không freeze như giá trị

* Actual user/manager/role membership.
* Organization state.
* Secrets/credential values; chỉ lưu credentialRef.
* Current child published version trước khi SubWorkflow Node activate.
* Ticket/Event runtime data.

## 4.3 Connector/action versioning

|  |
| --- |
| ConnectorDefinition: ERP  Action: CREATE\_PURCHASE\_ORDER  v1 {vendorId, amount}  v2 {supplierId, totalAmount, currency}  Published node pins actionVersion=2  Credential value remains runtime-resolved via credentialRef. |

|  |
| --- |
| **INVARIANT:** CONFIG/CONTRACT được version; SECRET là runtime reference. Published workflow không được thay behavior chỉ vì admin sửa mutable connector action contract. |

# 5. Node Configuration Contract và NodeType Registry

## 5.1 Node Configuration không phải arbitrary JSON

|  |
| --- |
| NodeDefinition  ├─ Identity: id, key, type, name, description  ├─ Config schema version  ├─ Designer metadata: position  ├─ Input contract: bindings + input schema  ├─ Common execution config  ├─ Human-task config?  ├─ Multi-instance?  ├─ SLA?  ├─ Failure/cancellation policy?  ├─ Output contract + variable mappings  └─ Type-specific config |

## 5.2 Stable node key

node.id là UUID kỹ thuật; node.key là stable business/definition key, unique trong WorkflowVersion và được dùng cho expression/reference. Đổi key là breaking change và dependency analysis phải hiển thị impact.

|  |
| --- |
| id = 4fb53a...  key = managerApproval  Reference:  ${nodes.managerApproval.latest.output.decision} |

## 5.3 configSchemaVersion

Mỗi Node Type có version schema cấu hình. Draft có thể migrate schema; Published version cũ vẫn được runtime hiểu theo schema version đã snapshot.

## 5.4 NodeTypeManifest

|  |
| --- |
| NodeTypeManifest  ├─ nodeType  ├─ currentConfigSchemaVersion  ├─ supportedCapabilities  ├─ inputSchema / outputSchema  ├─ outputPorts[]  ├─ configSchema (strict)  ├─ uiSchema  ├─ validator  └─ handler |

|  |  |  |  |  |
| --- | --- | --- | --- | --- |
| **Capability** | **APPROVAL** | **SYSTEM\_ACTION** | **SUB\_WORKFLOW** | **JOIN** |
| Human task | ✓ | – | – | – |
| Participant | ✓ | – | – | – |
| Form | ✓ | – | – | – |
| SLA | ✓ | optional technical timeout | – | – |
| Connector | – | ✓ | – | – |
| Multi-instance | ✓ | optional | optional | – |
| Output ports | APPROVED/REJECTED/... | SUCCESS/ERROR | COMPLETED/FAILED/... | COMPLETED |

## 5.5 Input binding contract

|  |
| --- |
| InputBinding {  target: "amount",  expression: "${ticket.data.amount}",  expectedType: MONEY,  required: true,  onMissing: ERROR | USE\_DEFAULT | NULL,  defaultValue?: ...  } |

Khi activation, binding được resolve + type-check, sau đó lưu inputSnapshot. Handler không query lại mutable context để tái diễn giải input cũ.

## 5.6 Participant → Task generation → Decision aggregation

|  |
| --- |
| ParticipantResolver  ↓  Resolved users / candidates  ↓  TaskGenerationStrategy  ↓  TaskExecution(s)  ↓  DecisionAggregationPolicy / CompletionPolicy  ↓  Node outcomePort |

|  |  |
| --- | --- |
| **Khái niệm** | **Ví dụ** |
| TaskGenerationStrategy | DIRECT\_SINGLE, CLAIMABLE\_POOL, TASK\_PER\_USER, SEQUENTIAL\_TASKS |
| CompletionPolicy | ALL, ANY, N\_OF\_M, PERCENTAGE cho non-decision tasks |
| DecisionAggregationPolicy | ALL\_APPROVE, ANY\_APPROVE, MAJORITY\_APPROVE, N\_OF\_M\_APPROVE, PERCENTAGE\_APPROVE |
| Reject behavior | FAIL\_FAST hoặc WAIT\_ALL tùy policy |

## 5.7 Output và variable mapping

|  |
| --- |
| Node handler output  ↓ persist outputSnapshot  ↓ explicit variableMappings only  Event variables  No arbitrary ctx.ticket.data mutation. |

## 5.8 Strict schema

|  |
| --- |
| **QUYẾT ĐỊNH CHỐT:** Unknown config field là blocking validation error. Không silently ignore typo như completionPolciy. UI metadata (x/y/collapsed tab) tách khỏi executable config. |

# 6. Dynamic Form Engine, Ticket Form và Runtime Requested Fields

## 6.1 Ba lớp field được hỗ trợ

|  |  |  |  |
| --- | --- | --- | --- |
| **Loại** | **Ai định nghĩa** | **Phạm vi** | **Workflow logic dùng?** |
| System Field | Platform | Toàn hệ thống | Có theo contract hệ thống. |
| Workflow Field | Workflow Owner/Editor trên Draft | WorkflowVersion | Có; type-safe, versioned. |
| Runtime Requested Field | Reviewer/Approver qua REQUEST\_REVISION | Ticket/Event/revision hiện tại | Không là static input contract; chủ yếu cho bổ sung hồ sơ/audit. |

|  |
| --- |
| **QUYẾT ĐỊNH CHỐT:** Không có Ticket Custom Field. Ticket Creator không được tự thêm field tùy ý. |

## 6.2 Form types

* TicketFormSchema: business input khi tạo/submit Ticket.
* TaskFormSchema: dữ liệu user nhập tại Human Task.
* RevisionRequestedForm: existing data + Runtime Requested Fields trong rework cycle.

## 6.3 Canonical field definition

|  |
| --- |
| FormFieldDefinition  ├─ fieldId / key  ├─ label, description, placeholder, order  ├─ type, defaultValue, sensitive  ├─ requirement: ALWAYS | NEVER | CONDITIONAL  ├─ visibility: ALWAYS | CONDITIONAL  ├─ editability: EDITABLE | READ\_ONLY | CONDITIONAL  ├─ validation: min/max/length/regex/safe rule  ├─ options: STATIC | DATA\_SOURCE  └─ semantic metadata: participantCapable, businessSubject, filterable/reportable |

## 6.4 “Required động”

Required động nghĩa là field đã có trong schema nhưng trạng thái bắt buộc phụ thuộc dữ liệu/context; không có nghĩa runtime tự sinh schema tùy ý.

|  |
| --- |
| quotation  type = FILE  requirement = CONDITIONAL  condition = ${form.amount >= 100000000} |

## 6.5 Workflow Owner thêm field mới

|  |
| --- |
| Edit Draft V4  ↓ Add Field costCenter  ↓ Dependency/type validation  ↓ Validate workflow  ↓ Publish V4  Running V3 Events remain V3. |

Draft được phép tạm incomplete. Save Draft không cần full validation; Publish bắt buộc full validation pass.

## 6.6 Field dependency analysis

Trước khi đổi key/type/xóa field, Builder phải hiển thị usages trong node input, condition, participant resolver, multi-instance, notification và integration mapping. Draft có thể vẫn lưu nhưng Publish bị chặn đến khi dependencies được sửa.

## 6.7 Runtime Requested Fields

Reviewer/Approver có thể yêu cầu field bổ sung khi REQUEST\_REVISION. Các field này được version theo RevisionRequest, không làm thay đổi WorkflowVersion và không trở thành field mặc định cho Ticket khác.

|  |  |
| --- | --- |
| **P1 allowed types** | **Ghi chú** |
| TEXT / TEXTAREA | Thông tin bổ sung. |
| NUMBER | Số liệu bổ sung. |
| DATE / DATETIME | Mốc thời gian. |
| SELECT / BOOLEAN | Lựa chọn có schema runtime. |
| FILE / FILE\_LIST | Tài liệu bổ sung. |

P1 không cho runtime requester/reviewer tạo USER/ROLE/DEPARTMENT field để tránh gián tiếp thay đổi participant/authorization. Nếu business cần loại này, phải mở capability có policy riêng.

# 7. EventContext, Canonical Type System, Mapping và Expression

## 7.1 Context namespaces

|  |
| --- |
| EventContext {  ticket,  creator,  organization,  event,  variables,  nodes,  item?,  task?,  actor?  } |

|  |  |
| --- | --- |
| **Namespace** | **Ý nghĩa** |
| ticket.data | Business input của Ticket / TicketRevision. |
| variables | Runtime workflow variables được khai báo. |
| nodes.<key>.latest.output | Output occurrence mới nhất trong scope phù hợp. |
| nodes.<key>.executions | Tất cả occurrences. |
| nodes.<key>.items | Multi-instance item outputs. |
| item | Current multi-instance item. |
| task | Current task context. |
| actor | Actor đang thực hiện command. |

## 7.2 Canonical type system

|  |
| --- |
| STRING, NUMBER, INTEGER, BOOLEAN  DATE, DATETIME, DURATION, MONEY  USER\_ID, USER, DEPARTMENT\_ID, GROUP\_ID  ENUM, OBJECT, ARRAY<T>  FILE\_REF, FILE\_LIST |

Form Builder, Expression Engine, Variable Engine, Connector mapping và Participant Resolver dùng cùng type system để validation xuyên suốt.

## 7.3 Expression safety

* Không arbitrary JavaScript/SQL.
* Operators allowlisted: EQ, NE, GT, GTE, LT, LTE, IN, CONTAINS, IS\_NULL, AND, OR, NOT...
* Reference path phải tồn tại trong schema tại design-time nếu là static contract.
* Ticket Form condition chỉ dùng form.\*, actor.\*, organization.\*, requestType.\* vì Event chưa chạy.
* Task/runtime expression có thể dùng ticket.\*, variables.\*, nodes.\*, item.\*, task.\*, actor.\* theo scope.

## 7.4 Repeated node output addressing

|  |
| --- |
| **QUYẾT ĐỊNH CHỐT:** Reference ${nodes.managerReview.output} bị coi là ambiguous nếu node có thể repeat/multi-instance. Dùng latest/executions/items/cycle-aware reference. |

# 8. Organization & Personnel Hierarchy

## 8.1 Vì sao tách hai cây

|  |
| --- |
| ORGANIZATION TREE REPORTING TREE  “Người này thuộc ĐÂU?” “Người này báo cáo cho AI?”  Company CTO Position  └─ Technology Division └─ IT Manager Position  └─ IT Department └─ Team Lead Position  └─ Application Team └─ Engineer Position |

Organization hierarchy mô tả cấu trúc đơn vị. Reporting hierarchy mô tả tuyến quản lý. Hai quan hệ thường liên quan nhưng không đồng nhất, đặc biệt với team, matrix/project reporting, kiêm nhiệm và cross-unit management.

## 8.2 OrganizationUnit

|  |  |
| --- | --- |
| **Field** | **Ý nghĩa** |
| id/code/name | Identity. |
| type | COMPANY, DIVISION, DEPARTMENT, TEAM, UNIT. |
| parentUnitId | Cây đơn vị. |
| managerPositionId | Position đứng đầu đơn vị; không lưu managerUserId trực tiếp. |
| status | ACTIVE/INACTIVE. |
| effectiveFrom/effectiveTo | History/effective dating khi cần. |

## 8.3 Position reporting tree

|  |  |
| --- | --- |
| **Field** | **Ý nghĩa** |
| id/code/name | Identity position. |
| orgUnitId | Position thuộc đơn vị nào. |
| reportsToPositionId | Cấp trên trong reporting hierarchy. |
| level | Optional metadata; không thay thế closure depth. |
| status | ACTIVE/VACANT/INACTIVE policy. |

## 8.4 PositionAssignment

|  |
| --- |
| PositionAssignment  employeeId  positionId  assignmentType  isPrimary  effectiveFrom  effectiveTo  status |

Employee không hard-code manager. Resolver lấy primary position → reportsToPosition → current active assignee. Thay người giữ vị trí không cần sửa workflow.

## 8.5 Optional ReportingRelation

Dùng cho temporary/matrix/project/functional reporting mà không phá Position tree. Primary override nếu tồn tại có thể được ưu tiên trước position hierarchy theo policy.

## 8.6 Closure tables để lookup nhanh

|  |
| --- |
| organization\_unit\_closure(ancestor\_unit\_id, descendant\_unit\_id, depth)  position\_closure(ancestor\_position\_id, descendant\_position\_id, depth) |

Closure table cho phép query department ancestors, N-level manager, all subordinates và management ancestry nhanh, tránh recursive N+1 query runtime.

## 8.7 OrganizationResolver

|  |
| --- |
| getPrimaryUnit(employeeId)  getPrimaryPosition(employeeId)  getDirectManager(employeeId)  getNthManager(employeeId, level)  getDepartmentHead(employeeId)  getSubordinates(managerId, depth)  isManagerOf(actorId, employeeId, maxDepth?) |

## 8.8 Hierarchy validation

* Không self-reporting.
* Không cycle trong Position hierarchy hoặc Organization tree.
* Primary assignment phải unambiguous theo effective time.
* Vacant/inactive position trả structured resolution status, không random skip.
* Cross-org reporting có thể warning thay vì blocking vì matrix organization có thể hợp lệ.

|  |
| --- |
| **QUYẾT ĐỊNH CHỐT:** Organization Directory là source of truth runtime. Workflow snapshot participant khi activation; org thay đổi sau đó chỉ ảnh hưởng node activate sau, không silent reassign task cũ. |

# 9. Dynamic Participant Resolution

## 9.1 Nguyên tắc

|  |
| --- |
| **QUYẾT ĐỊNH CHỐT:** Definition quyết định “cách tìm ai”; runtime quyết định “người thực tế là ai”. |

## 9.2 Generic resolver primitives

|  |  |
| --- | --- |
| **Resolver** | **Ví dụ** |
| FIXED\_USER | User cố định. |
| ROLE\_MEMBERS | Resolve role + scope. |
| GROUP\_MEMBERS | Resolve group. |
| REQUEST\_FIELD | USER/USER\_LIST field trong Ticket contract. |
| MANAGER\_OF | Subject=TICKET\_CREATOR/CURRENT\_ITEM/REQUEST\_FIELD/PREVIOUS\_PARTICIPANT; level=N. |
| HEAD\_OF\_UNIT | Subject + unitType=TEAM/DEPARTMENT/DIVISION. |
| PREVIOUS\_PARTICIPANT | Actor/assignee bước trước. |
| NODE\_OUTPUT | User ref từ output typed. |
| EXPRESSION | Safe expression trả USER/USER\_LIST. |
| CUSTOM\_RESOLVER | Plugin do platform đăng ký. |

## 9.3 Fallback chain

|  |
| --- |
| Primary: MANAGER\_OF(creator, level=1)  Fallback 1: HEAD\_OF\_UNIT(creator, DEPARTMENT)  Fallback 2: WORKFLOW\_OWNER  OnMissing: CREATE\_MANUAL\_TASK | FAIL\_NODE |

## 9.4 Resolution result

|  |
| --- |
| ResolutionResult.status =  RESOLVED | VACANT | INACTIVE\_ASSIGNEE | NOT\_FOUND | AMBIGUOUS  users = [...] |

Participant Resolver áp fallback; OrganizationResolver không tự đoán business fallback.

## 9.5 Snapshot và inactive assignee

Nếu assignee bị disable sau khi task active, assignment snapshot vẫn giữ. P1 mặc định phát attention/operational alert và yêu cầu explicit reassign; auto reassign chỉ khi workflow/policy cho phép và phải lưu AssignmentHistory reason=ASSIGNEE\_INACTIVE.

# 10. Human Task Runtime, Assignment và Decision Aggregation

## 10.1 Task chỉ sinh khi node activate

Không pre-create toàn bộ task khi Event start. Nhánh không được đi qua thì không sinh task.

## 10.2 Task generation strategies

|  |  |
| --- | --- |
| **Strategy** | **Semantics** |
| DIRECT\_SINGLE | Resolve đúng một assignee, tạo một task. |
| CLAIMABLE\_POOL | Một task có N candidates; một người claim thành assignee. |
| TASK\_PER\_USER | N resolved users → N TaskExecutions. |
| SEQUENTIAL\_TASKS | Tạo/activate task lần lượt theo order. |

## 10.3 Task lifecycle

|  |  |
| --- | --- |
| **State** | **Ý nghĩa** |
| READY | Có thể xử lý/claim. |
| CLAIMED | Candidate đã claim. |
| IN\_PROGRESS | Đang làm. |
| COMPLETED | Task terminal; business decision nằm outcome. |
| CANCELLED | Bị hủy bởi Event/path/threshold/reassign policy. |
| EXPIRED | Quá hạn theo policy. |

## 10.4 Task outcome

APPROVED, REJECTED, RETURNED, SUBMITTED, ACKNOWLEDGED... là outcome, không phải lifecycle state.

## 10.5 Decision aggregation

|  |  |
| --- | --- |
| **Policy** | **Ví dụ** |
| ALL\_APPROVE | Tất cả approver phải approve. |
| ANY\_APPROVE | Một approve đủ. |
| MAJORITY\_APPROVE | Approve > 50%. |
| N\_OF\_M\_APPROVE | Đủ N approve. |
| PERCENTAGE\_APPROVE | Đủ % threshold. |

Approval có rejectBehavior=FAIL\_FAST hoặc WAIT\_ALL. Non-decision tasks dùng CompletionPolicy ALL/ANY/N\_OF\_M/PERCENTAGE.

## 10.6 Reassign vs Delegate

|  |  |
| --- | --- |
| **Khái niệm** | **Semantics** |
| REASSIGN | C trở thành assignee thực tế; B hết trách nhiệm. History B→C. |
| DELEGATE – extension | B vẫn accountable; C act on behalf of B. Audit phải ghi actor + principal. |

P0/P1 chỉ cần Reassign; data model/API không nên khóa đường Delegation về sau.

# 11. Multi-instance / Dynamic Fan-out

## 11.1 Mục đích

Dùng khi collection chỉ biết tại runtime: đánh giá N nhân viên, duyệt N tài liệu, kiểm tra N asset. Không tạo N Event; vẫn là một Event.

|  |
| --- |
| MultiInstanceConfig {  collection: ${ticket.data.evaluationTargets}  itemVariable: employee  executionMode: PARALLEL | SEQUENTIAL  subject: ${employee}  completionPolicy: ALL | ANY | N\_OF\_M | PERCENTAGE  remainingItemPolicy: CANCEL\_REMAINING | KEEP\_RUNNING  } |

## 11.2 Runtime correlation

|  |
| --- |
| Parent NodeExecution  ├─ ItemExecution B -> Task...  ├─ ItemExecution C -> Task...  └─ ItemExecution D -> Task...  Each item has itemToken / ordinal / itemKey and isolated output. |

## 11.3 Threshold reached

Nếu ANY/N\_OF\_M/PERCENTAGE đạt threshold, remainingItemPolicy bắt buộc xác định task/item còn lại. Default khuyến nghị CANCEL\_REMAINING. KEEP\_RUNNING chỉ dùng khi business vẫn muốn thu thập dữ liệu và phải bảo đảm không route downstream lần nữa.

## 11.4 Rework scope

Rework trong multi-instance phải có scope CURRENT\_ITEM hoặc WHOLE\_NODE. P1 khuyến nghị CURRENT\_ITEM khi return nằm trong item scope; validator chặn target làm mất item context.

# 12. Routing & Next-node Selection – Runtime Algorithm

## 12.1 Invariant trung tâm

|  |
| --- |
| **QUYẾT ĐỊNH CHỐT:** NodeHandler không quyết định target. EdgeDefinition là source of truth duy nhất về destination. |

|  |
| --- |
| Task / Callback / Timer / Handler  ↓  NodeExecution Complete  ↓  outcomePort  ↓  RoutingService  ↓  EdgeDefinition  ↓  RoutingDecision  ↓  ActivationToken(s)  ↓  NodeActivationService  ↓  NodeExecution(s) |

## 12.2 EdgeDefinition

|  |  |
| --- | --- |
| **Field** | **Ý nghĩa** |
| sourceNodeId | Node nguồn. |
| sourcePort | APPROVED/REJECTED/SUCCESS/DEFAULT/... |
| targetNodeId | Node đích. |
| condition | Safe condition tùy chọn. |
| priority | Lower number = higher priority. |
| isDefault | Fallback route. |
| transitionType | NORMAL | CONDITIONAL | REWORK | RETURN. |
| label | Business-readable label. |

## 12.3 Routing modes

|  |  |
| --- | --- |
| **Mode** | **Semantics** |
| SINGLE\_BY\_PORT | Port xác định một route business. |
| EXCLUSIVE\_CONDITIONAL | Evaluate matches; chọn một edge deterministic theo priority/default. |
| ALL\_OUTGOING | Parallel Split tạo token cho tất cả outgoing edge. |
| ALL\_MATCHING | Optional selective parallel; chỉ dùng nếu NodeType support. |
| NONE | End/no routing. |

## 12.4 Runtime algorithm

1. Verify source NodeExecution đã COMPLETED và Event chưa terminal.

2. Lấy outcomePort và outgoing edges theo sourceNode + port.

3. Evaluate conditions bằng EventContext sau khi source output/variable mappings đã persist.

4. Áp routingMode; exclusive dùng deterministic priority/default, parallel có thể chọn N edges.

5. Nếu no-match ngoài semantics cho phép → ROUTING\_NO\_MATCH runtime failure.

6. Tạo RoutingDecision + ActivationToken(s) idempotently.

7. NodeActivationService tạo downstream NodeExecution(s) với path/cycle/item correlation phù hợp.

8. Audit route decision. Không gọi next node trực tiếp từ controller/handler.

## 12.5 RoutingDecision audit

|  |
| --- |
| RoutingDecision {  eventId, sourceNodeExecutionId, outcomePort, routingMode,  evaluatedEdges[{edgeId, conditionResult, priority}],  selectedEdges[], decidedAt  } |

Mục tiêu: production có thể trả lời “vì sao Ticket này đi Director thay vì Manager?”.

## 12.6 Runtime không DFS/BFS toàn workflow

|  |
| --- |
| **QUYẾT ĐỊNH CHỐT:** Graph traversal dùng ở Validate/Simulation. Runtime là token-driven / activation-driven state transition, không for-loop/DFS toàn graph. |

# 13. Graph Semantics: Condition, Parallel Split/Join và Tokens

## 13.1 Condition routing

Nếu outgoing conditions không thể chứng minh exhaustive bằng schema/enum, phải có default edge. Nếu nhiều edge có thể match, priority phải deterministic. Validator có thể warning overlap hoặc block ambiguity.

## 13.2 Multiple incoming edge không tự merge

|  |
| --- |
| A ─┐  ├→ C  B ─┘  Nếu A và B cùng active, C có thể có 2 NodeExecution occurrences.  Muốn synchronization một lần: A/B → JOIN → C. |

## 13.3 Parallel Split

|  |
| --- |
| PARALLEL\_SPLIT PS1  ├─ PathToken P1 → Finance  ├─ PathToken P2 → Legal  └─ PathToken P3 → Security |

## 13.4 Join scope

Join không đếm “số NodeExecution complete theo node id”. Join aggregate token theo splitExecutionId/joinScopeId/pathToken để nested parallel không consume nhầm token.

|  |  |
| --- | --- |
| **Join policy** | **Semantics** |
| ALL | Chờ tất cả required tokens trong split scope. |
| ANY | Một token đủ; remainingBranchPolicy bắt buộc. |
| N\_OF\_M – extension | Đủ N token; mở sau nếu requirement có. |

## 13.5 ANY Join remaining branches

remainingBranchPolicy = CANCEL\_REMAINING hoặc KEEP\_RUNNING. Default business workflow là CANCEL\_REMAINING. Cancellation chỉ áp sibling paths trong cùng split scope.

## 13.6 End Node trong parallel

P1 yêu cầu parallel branches Join trước End. Tránh semantics “một branch tới End thì toàn Event kết thúc” khi sibling vẫn active. Event terminal khi graph/runtime token semantics chứng minh không còn active work hoặc explicit terminate command.

# 14. Rework, Request Revision và Controlled Cycles

## 14.1 Cycle không đồng nghĩa invalid

|  |
| --- |
| Requester Form  ↓  Manager Review  ├─ APPROVED → Finance  └─ REVISION\_REQUESTED  ↓  Requester Form (new cycle) |

## 14.2 Intentional rework edge

|  |
| --- |
| Edge:  sourcePort = REVISION\_REQUESTED  targetNode = requesterForm  transitionType = REWORK  reworkPolicy.maxIterations = 5  onExhausted = ROUTE\_PORT(REWORK\_EXHAUSTED) | FAIL\_EVENT | MANUAL\_REVIEW |

## 14.3 Runtime behavior

Quay lại node cũ luôn tạo NodeExecution mới. Execution cũ giữ COMPLETED. cycleId/iteration tăng và task/SLA mới được tạo/snapshot.

## 14.4 Cycle validation

Validator dùng graph/SCC detection để tìm cycle. Cycle chỉ hợp lệ khi có intentional REWORK/RETURN semantic, bounded maxIterations, exhaustion behavior và scope tương thích. Undeclared/unbounded cycle là blocking error.

## 14.5 Rework + parallel/multi-instance

* P1 chặn rework edge tùy ý vượt Parallel/Join scope nếu chưa có explicit scope reset semantics.
* Multi-instance rework phải chỉ rõ CURRENT\_ITEM hoặc WHOLE\_NODE.
* Output sau rework nên dùng latest/cycle-aware reference; stale occurrence reference có thể warning.

## 14.6 Ticket Revision

Nếu dữ liệu được bổ sung/sửa là business data thật của Ticket, tạo TicketRevision/data history thay vì mutate vô hình. Event giữ startedTicketRevisionId; execution cycle có thể tham chiếu revision mới trong cùng Event theo rework policy.

# 15. SLA, Business Calendar, Escalation và Notification

## 15.1 SLA config

|  |
| --- |
| SLAConfig  dueRule  businessCalendarId?  reminders[]  escalations[]  timeoutAction  resolutionPolicy |

## 15.2 Business Calendar

Nếu SLA dùng working hours/days, Basic BusinessCalendar nên vào P1: timezone, working days, working hours, holidays. Runtime persist timestamps bằng UTC/Instant; calendar có timezone, ví dụ Asia/Ho\_Chi\_Minh.

## 15.3 Snapshot

Khi task/node activate, dueAt được tính và snapshot. Calendar/rule đổi sau đó không âm thầm sửa dueAt active trừ explicit recalculation command/policy.

## 15.4 Escalation

Escalation participant dùng cùng ParticipantResolver/OrganizationResolver. Mọi auto reassign phải audit original assignee, new assignee và reason.

## 15.5 Notification

Notification recipient resolve từ resolver infrastructure. Dispatch có dedupKey; notification failure mặc định không fail business Event trừ policy explicit. Template/config semantics thuộc execution package.

# 16. Dynamic Sub-workflow

## 16.1 Process boundary

SubWorkflow Node dùng khi bước thực chất là một business process độc lập có node/task/SLA/participants/history riêng. Parent tạo Child Event; không inline runtime child vào parent.

|  |
| --- |
| Parent Event E001  SubWorkflow NodeExecution N10  ↓ creates  Child Event E900  parentEventId=E001  parentNodeExecutionId=N10  rootEventId=E001 |

## 16.2 Version resolution

Default: tại lần đầu SubWorkflow Node activate, resolve current Published version của child WorkflowDefinition và tạo Child Event bind version đó. Child không đổi version giữa chừng. FIXED\_VERSION chỉ là optional compliance extension.

## 16.3 Execution modes

|  |  |
| --- | --- |
| **Mode** | **Behavior** |
| WAIT\_FOR\_COMPLETION | Parent node WAITING\_CHILD; child terminal → parent outcomePort rồi routing. |
| FIRE\_AND\_CONTINUE | Child tạo thành công → parent node complete và route; child chạy độc lập. |

## 16.4 Cancellation

Default WAIT\_FOR\_COMPLETION → PROPAGATE cancel child nếu active. FIRE\_AND\_CONTINUE → DETACH mặc định. Config có cancellationPolicy=PROPAGATE|DETACH.

## 16.5 Input/output contract

Parent→child và child→parent mapping explicit. Không shared mutable context. Child không tự mutate parent Ticket; business record update phải đi qua explicit System Action/command.

## 16.6 Recursion

P1 chặn direct/indirect subworkflow recursion tại publish. Optional max depth chỉ mở khi có requirement rõ.

# 17. System Action / Integration / Callback / Compensation

## 17.1 System Action khác Sub-workflow

System Action là concrete capability/system operation; Sub-workflow là process boundary. Không dùng sub-workflow chỉ để gọi một API, và không dùng SystemAction để nhét một process lớn.

## 17.2 Connector Registry

|  |
| --- |
| SystemActionNode  connectorKey = ERP  actionKey = CREATE\_PURCHASE\_ORDER  actionVersion = 2  inputBindings  outputSchema  retry/failure policy |

Technical Admin quản lý connector/host/auth/action contract; Workflow Owner chỉ chọn action được allowlist. Không arbitrary URL/raw SQL.

## 17.3 IntegrationExecution

|  |  |
| --- | --- |
| **Field** | **Ý nghĩa** |
| eventId/nodeExecutionId | Correlation. |
| connectorKey/actionKey/actionVersion | Pinned executable contract. |
| attempt | Retry attempt. |
| status | RUNNING/SUCCEEDED/FAILED/WAITING\_CALLBACK... |
| idempotencyKey | Stable logical key. |
| request/response snapshot | Masked/sanitized. |
| externalRequestId/callbackCorrelationId | Async correlation. |
| error | Mapped error category. |

## 17.4 Transaction boundary

|  |
| --- |
| TX1: mark execution running + persist integration attempt → COMMIT  External call (no DB transaction held)  TX2: persist result/outcome + activation job → COMMIT |

## 17.5 Async callback hardening

1. Generate opaque callbackCorrelationId per IntegrationExecution.

2. Callback validates connector identity/signature/timestamp/correlation/externalEventId.

3. Require target execution WAITING\_EXTERNAL and not terminal.

4. Duplicate callback/externalEventId consumed idempotently.

5. Expired/late callback logged as late event; never resume terminal Event/Node.

## 17.6 Compensation

Cancel/Terminate Event không rollback external side effects. Optional CompensationPolicy = NONE | MANUAL | ACTION. Compensation là business operation explicit, ví dụ CREATE\_PO compensation=CANCEL\_PO; không xây full saga engine P0.

# 18. File & Attachment Model

## 18.1 FILE\_REF, không binary/base64 trong context

|  |
| --- |
| FileRef {  fileId, originalName, mimeType, size, checksum,  storageKey, uploadedBy, uploadedAt, scanStatus  } |

Ticket data / task output chỉ lưu fileId/ref. Bytes ở object storage.

## 18.2 Policies

|  |  |
| --- | --- |
| **Policy** | **Nội dung** |
| Type/count/size | allowedMimeTypes, maxFileSize, maxFileCount. |
| Security scan | PENDING\_SCAN, CLEAN, QUARANTINED/REJECTED. |
| Permission | Download dựa trên Ticket/Event visibility + field policy. |
| Retention | File retention có thể khác Event/audit retention. |
| Sensitive | Mask metadata/content preview theo permission. |

# 19. Ticket & Event Lifecycle

## 19.1 Ticket states

|  |
| --- |
| DRAFT → SUBMITTED → IN\_PROGRESS → COMPLETED / REJECTED / CANCELLED |

## 19.2 Event states/outcome

|  |
| --- |
| EventStatus = CREATED | RUNNING | WAITING | COMPLETED | FAILED | CANCELLED | TERMINATED  EventOutcome = APPROVED | REJECTED | SUCCESS | WITHDRAWN | domain-defined |

## 19.3 Waiting semantics

Event chỉ WAITING khi không có branch runnable/active và ít nhất một wait tồn tại. Nếu một branch chờ callback nhưng branch khác đang chạy, Event vẫn RUNNING. WaitReason có thể HUMAN\_TASK, TIMER, EXTERNAL\_CALLBACK, CHILD\_EVENT, JOIN, RETRY\_BACKOFF.

## 19.4 Cancel vs Terminate

|  |  |
| --- | --- |
| **Command** | **Semantics** |
| Cancel | Business/user intentional stop; propagation theo runtime policy. |
| Terminate | Admin/emergency force stop; reason bắt buộc; không phải rollback external effect. |

## 19.5 Reopen/Restart

Không reopen terminal Event. Reopen Ticket/Restart tạo Event mới, thường resolve current Published version. Lưu previousEventId/restartedFromEventId. Retry là retry failed execution trong cùng Event; Restart là process mới.

## 19.6 Một active root Event

Default tối đa một active primary/root Event cho mỗi Ticket. Child Event không tính vào uniqueness của root.

# 20. Commands, Authorization và Visibility

## 20.1 Không generic PATCH status

|  |
| --- |
| SAVE\_DRAFT\_TICKET  SUBMIT\_TICKET / RESUBMIT\_TICKET  CLAIM\_TASK / UNCLAIM\_TASK  COMPLETE\_TASK / APPROVE\_TASK / REJECT\_TASK / RETURN\_TASK  REASSIGN\_TASK  CANCEL\_TICKET / CANCEL\_EVENT  RETRY\_NODE / RETRY\_INTEGRATION  RESUME\_EXTERNAL  TERMINATE\_EVENT  FORCE\_COMPLETE\_TASK (restricted) |

## 20.2 Command guards

* Actor authorization.
* Current state allows transition.
* commandId idempotency.
* expectedVersion/revision optimistic concurrency.
* Business preconditions.
* Audit actor/reason/result.

## 20.3 Visibility policy

PolicyResolver quyết định ai được view Ticket/Event, comment, cancel, reassign, act. Subjects có thể gồm creator, current/previous participant, manager, same department, workflow owner, watcher, admin/role. Backend là source of truth.

# 21. Concurrency, Idempotency, Race và Crash Recovery

## 21.1 Baseline

Optimistic locking là mặc định. Pessimistic/short row lock chỉ dùng cho completion/join aggregate/routing race nơi cần serialized decision.

## 21.2 Command idempotency

Duplicate commandId thành công trả original result khi có thể. Cùng expectedVersion nhưng commandId khác và state đã đổi → 409 conflict.

## 21.3 Activation idempotency

|  |
| --- |
| activationKey ≈ eventId + sourceExecutionId + edgeId + pathToken + cycleId + itemToken |

Không unique(eventId,nodeDefinitionId) vì intentional repeated execution phải được phép.

## 21.4 Race rule

|  |
| --- |
| **INVARIANT:** Terminal transition đã commit thì command/event đến sau không được làm runtime object sống lại. |

|  |
| --- |
| Cancel wins → Event CANCELLED  Later ERP callback arrives → audit LATE\_CALLBACK\_RECEIVED  No resume, no downstream activation. |

## 21.5 Completion vs cancellation

Task đã COMPLETED trước khi Event cancel thì giữ COMPLETED trong history; chỉ active sibling task/node bị CANCELLED. Không rewrite lịch sử để “đồng bộ trạng thái”.

## 21.6 Worker lease recovery

Durable jobs có state + leaseOwner + leaseUntil + attempts. Worker crash → lease expire → worker khác claim lại. Handler/job phải idempotent.

# 22. Validation Engine, Publish Gate và Simulation

## 22.1 Validate là “compiler/static analysis”

Validate không chạy workflow thật. Mục tiêu chứng minh definition đủ cấu hình, type-safe, graph/routing deterministic và executable theo semantics platform. Business order đúng ý tổ chức vẫn cần human review/simulation/governance.

## 22.2 Validation pipeline

1. Definition/JSON schema validation.

2. Node config schema + configSchemaVersion.

3. Reference/path/type validation.

4. Graph structural validation.

5. Node semantic validation.

6. Routing completeness/determinism.

7. Participant/task validation.

8. Multi-instance validation.

9. Parallel/join/token scope validation.

10. Rework/loop validation.

11. Form/variable validation.

12. SLA/calendar validation.

13. Integration/connector contract validation.

14. Sub-workflow validation.

15. Permission/security validation.

16. Version/revision validation.

17. Cross-node execution validation.

## 22.3 Graph completeness rules

* Exactly one START trong P0; ít nhất một END.
* START không incoming; END không outgoing.
* No dangling edge / invalid port / missing target.
* Mọi reachable non-terminal node không được dead-end.
* Unreachable node là ERROR trong publishable Draft.
* Mọi active output port/outcome phải có route hoặc explicit terminal/failure policy.
* Conditional routing phải deterministic và có default khi không chứng minh exhaustive.
* Loop/rework phải intentional + bounded + exhaustion behavior.

## 22.4 Join/Multi-instance validation

* Collection phải ARRAY/LIST và item type infer được.
* Threshold policy phải coherent.
* ANY/N\_OF\_M phải có remainingItemPolicy.
* Join phải pair/scope đúng split; ALL Join không được chờ token không thể tới.
* ANY Join phải có remainingBranchPolicy.

## 22.5 Severity

|  |  |  |
| --- | --- | --- |
| **Severity** | **Publish behavior** | **Ví dụ** |
| ERROR | Block publish | NO\_START, INVALID\_CONFIG, UNHANDLED\_PORT, JOIN\_CAN\_NEVER\_COMPLETE. |
| WARNING | Publish được | Dynamic resolver sample empty, cross-org reporting, missing optional SLA. |
| ACK\_REQUIRED\_WARNING | Phải acknowledge nếu policy bật | ANY join cancels active siblings, child suspended. |
| INFO | Quality hint | Generic node name, no description. |

## 22.6 ValidationReport

|  |
| --- |
| ValidationReport {  valid, publishable,  workflowVersionId, revision, definitionChecksum, validatedAt,  summary{errors,warnings,info},  issues[{code,severity,resourceType,resourceId,fieldPath,message,suggestion}]  } |

FE click issue phải focus node/field tương ứng.

## 22.7 Publish luôn validate lại

Validation result gắn revision/checksum. Publish server-side full validate lại trong publish flow; không tin validation cũ sau khi Draft đã sửa.

## 22.8 Simulation

Simulation chạy sample context, không tạo real side effect. Connector dùng stub/dry-run. Hiển thị resolved participants, selected routes, fan-out count, join behavior, sub-workflow mapping và warnings.

# 23. Publish/Version/Change Management, Diff và Rollback

## 23.1 Lifecycle

|  |
| --- |
| WorkflowDefinition.lifecycle = ACTIVE | SUSPENDED | ARCHIVED  WorkflowVersion.status = DRAFT | PUBLISHED | SUPERSEDED | ARCHIVED |

## 23.2 Publish

Only Draft publishable. Publish tạo immutable execution package, version number system-generated, chuyển previous Published → SUPERSEDED và update currentPublishedVersionId atomically.

## 23.3 Suspend/Archive

Suspend chặn new root Event nhưng running Events tiếp tục. Archive ẩn khỏi active catalog và chặn business mới; không hard-delete artifact có runtime reference.

## 23.4 Semantic diff

|  |
| --- |
| V12 → V13  + Added Legal Review  ~ Manager Approval SLA 24h → 12h  ~ costCenter optional → required  ~ participant ROLE\_MANAGER → MANAGER\_OF(creator)  - Removed Finance email notification |

Diff phục vụ review, audit, incident investigation và publish governance.

## 23.5 Rollback

Default rollback không nhảy pointer ngược. Clone known-good version thành Draft mới → Validate → Publish monotonic version mới. Ví dụ V6 good, V7 bad → V8 clone V6. Lưu basedOnVersionId/rollbackOfVersionId.

## 23.6 Maker-checker – extension

Architecture có thể mở publishGovernance=DIRECT|REQUIRE\_APPROVAL. Không bắt buộc P0 nếu requirement chưa có; không nên thêm state phức tạp sớm.

# 24. Monitoring, Audit, Operational Recovery và Outbox

## 24.1 Event detail

* Render graph đúng WorkflowVersion đã chạy.
* Highlight nhiều NodeExecution occurrences theo cycle/path/item.
* Timeline trigger → activation → task → command → route → integration → completion.
* Show participant snapshots, assignment/reassignment, child links.
* Context inspector mask sensitive fields.

## 24.2 Audit events tối thiểu

|  |  |
| --- | --- |
| **Nhóm** | **Events** |
| Definition | DRAFT\_SAVED, VALIDATED, PUBLISHED, SUSPENDED, ARCHIVED. |
| Runtime | EVENT\_STARTED, NODE\_ACTIVATED, NODE\_COMPLETED, ROUTE\_SELECTED. |
| Task | TASK\_CREATED, CLAIMED, COMPLETED, REASSIGNED, CANCELLED. |
| Integration | ACTION\_STARTED, RETRIED, SUCCEEDED, FAILED, CALLBACK\_RECEIVED/LATE. |
| Admin | FORCE\_COMPLETE, TERMINATE, MANUAL\_RECOVERY. |

## 24.3 Durable job/outbox

|  |
| --- |
| **ROADMAP CHỈNH:** Đưa baseline outbox/durable job vào P2 Integration, không chờ P3. |

|  |
| --- |
| TX: NodeExecution COMPLETED  + insert workflow\_job ACTIVATE\_NEXT  + insert outbox\_event if needed  COMMIT  Worker claims with lease → execute idempotently → DONE |

## 24.4 Dead-letter / operational recovery

Failed permanently jobs/executions phải xuất hiện trong operational queue; Operator có thể Retry, Resolve manually, Create manual task, Goto fallback hoặc Terminate theo quyền. Override luôn yêu cầu reason + audit.

# 25. Mô hình dữ liệu vật lý & Database Design chốt

## 25.1 Definition tables

|  |  |
| --- | --- |
| **Table** | **Key columns** |
| workflow\_definitions | id, key, name, lifecycle, current\_published\_version\_id, active\_draft\_version\_id, lock\_version |
| workflow\_versions | id, definition\_id, version\_no, status, revision, checksum, published\_at, based\_on\_version\_id |
| workflow\_nodes | version\_id, node\_id, node\_key, node\_type, config\_schema\_version, config\_json, input\_schema\_json, output\_schema\_json, position\_json |
| workflow\_edges | version\_id, edge\_id, source\_node\_id, source\_port, target\_node\_id, condition\_json, priority, is\_default, transition\_type, config\_json |
| workflow\_forms | version\_id, form\_key, form\_type, schema\_json |
| workflow\_variables | version\_id, key, type, default\_json, mutable, sensitive |
| request\_types | id, key, name, category, active, workflow\_definition\_id, creation\_policy\_json |

## 25.2 Runtime tables

|  |  |
| --- | --- |
| **Table** | **Key columns** |
| tickets | id, request\_type\_id, creator\_id, status, data\_json, data\_revision, created\_at, lock\_version |
| ticket\_revisions | id, ticket\_id, revision\_no, data\_snapshot\_json, submitted\_by, submitted\_at |
| events | id, ticket\_id, workflow\_version\_id, status, outcome, root\_event\_id, parent\_event\_id, previous\_event\_id, lock\_version |
| node\_executions | id, event\_id, node\_definition\_id, cycle\_id, iteration, path\_token, item\_token, status, outcome\_port, input\_json, output\_json, lock\_version |
| task\_executions | id, node\_execution\_id, status, outcome, assignee\_id, due\_at, lock\_version |
| task\_assignments/history | task\_id, actor/candidate/assignee, assignment\_type, from/to, reason |
| revision\_requests | id, event\_id, source\_task\_id, requested\_by, comment, status |
| revision\_requested\_fields | revision\_request\_id, field\_id, type, schema\_json, value\_json |
| routing\_decisions | source\_node\_execution\_id, outcome\_port, routing\_mode, evaluated\_json, selected\_json |
| integration\_executions | node\_execution\_id, connector/action/version, attempt, status, idempotency\_key, correlation ids, masked req/res |
| workflow\_jobs | id, type, payload\_ref, status, attempts, lease\_owner, lease\_until, dedup\_key |
| outbox\_events | id, event\_type, aggregate\_id, payload\_json, status, created\_at |
| audit\_events | id, aggregate\_type/id, event\_type, actor, timestamp, metadata\_json |

## 25.3 Organization tables

|  |  |
| --- | --- |
| **Table** | **Key columns** |
| employees | id, user\_id, employee\_code, status |
| organization\_units | id, code, name, type, parent\_unit\_id, manager\_position\_id, status |
| organization\_unit\_closure | ancestor\_unit\_id, descendant\_unit\_id, depth |
| positions | id, code, name, org\_unit\_id, reports\_to\_position\_id, status |
| position\_closure | ancestor\_position\_id, descendant\_position\_id, depth |
| position\_assignments | id, employee\_id, position\_id, is\_primary, assignment\_type, effective\_from, effective\_to, status |
| reporting\_relations – optional | subordinate\_employee\_id, manager\_employee\_id, relation\_type, priority, effective range |

## 25.4 Constraints/indexes quan trọng

* Unique workflow definition key.
* Unique node\_key within WorkflowVersion.
* Published immutable at service/DB policy boundary.
* One active root Event per Ticket by partial unique constraint if business rule applies.
* commandId/dedupKey unique per command scope.
* Activation logical key unique theo occurrence correlation.
* Closure indexes ancestor/depth và descendant/depth.
* Partial indexes active PositionAssignment/effective\_to IS NULL.
* No unique(event\_id,node\_definition\_id).

## 25.5 Quyết định thiết kế database chốt

Phần 25.1–25.4 phía trên là danh mục baseline. Từ mục 25.5 trở đi là physical database design chi tiết, có hiệu lực normative cho implementation. Thiết kế này hiện thực hóa trực tiếp các semantics đã chốt về immutable WorkflowVersion, Ticket/Event boundary, token-driven routing, multi-instance, Join, rework, participant snapshot, organization hierarchy, idempotency, integration và durable recovery.

|  |  |
| --- | --- |
| **Nguyên tắc** | **Quyết định chốt** |
| Database engine | PostgreSQL 17 (hoặc version production tương thích). Không cần Neo4j/MongoDB cho core hiện tại. |
| Model | Hybrid Relational + JSONB. |
| Relational | Identity, lifecycle state, ownership, FK relationship, correlation, versioning, concurrency, idempotency, fields cần index/search. |
| JSONB | Dynamic node config, form schema, expression/policy config, Ticket business data, input/output snapshot, sanitized external payload. |
| Timestamps | TIMESTAMPTZ/Instant; persist UTC. BusinessCalendar lưu timezone riêng. |
| Concurrency | Optimistic lock\_version mặc định; short row lock cho completion/join/routing aggregate khi cần. |
| History | Runtime decision/audit/history append-only; không rewrite terminal execution để “đồng bộ trạng thái”. |
| IDs | Ưu tiên UUID cho aggregate/runtime mới. Nếu MVP đang dùng BIGINT thì migrate incremental; không rewrite chỉ vì loại ID. |
| Enum storage | Ưu tiên VARCHAR + application/check constraints cho lifecycle dễ mở rộng; không lạm dụng PostgreSQL native ENUM. |

## 25.6 ERD tổng thể và boundary lưu trữ

REQUEST / DEFINITION

RequestType ─────→ WorkflowDefinition ─────→ WorkflowVersion
 ├─ WorkflowNode
 ├─ WorkflowEdge
 ├─ WorkflowForm
 ├─ WorkflowVariable
 └─ ValidationRun / Issue

RUNTIME

Ticket ──┬─ TicketRevision
 ├─ TicketSubject
 └─ Event ──┬─ NodeExecution ──┬─ ItemExecution
 │ ├─ TaskExecution
 │ ├─ ParticipantSnapshot
 │ └─ IntegrationExecution
 ├─ RoutingDecision
 ├─ ActivationToken
 ├─ RevisionRequest
 ├─ SLAExecution
 └─ AuditEvent

ORGANIZATION

OrganizationUnit ↔ OrganizationUnitClosure
Position ↔ PositionClosure
Position ──→ PositionAssignment ──→ Employee
Optional ReportingRelation

EventContext không được persist như một giant mutable JSON blob. Runtime context được dựng từ Ticket/TicketRevision, Event variables, NodeExecution snapshots, participant/organization resolution và current runtime scope. Điều này giữ audit/replay rõ ràng và giảm hidden mutation.

## 25.7 Request Catalog và Definition schema

|  |  |  |  |
| --- | --- | --- | --- |
| **Table** | **Mục đích** | **Các cột chốt** | **Constraint / Index chính** |
| request\_types | Business catalog để end user chọn loại yêu cầu. | id, key, name, description, category, workflow\_definition\_id, active, creation\_policy\_json, created\_at, updated\_at, lock\_version | UNIQUE(key); index(active, category). P0: 1 RequestType → 1 active WorkflowDefinition. |
| workflow\_definitions | Identity workflow qua nhiều version. | id, key, name, description, lifecycle, owner\_id, current\_published\_version\_id, active\_draft\_version\_id, created\_by, timestamps, lock\_version | UNIQUE(key). Pointer version nullable và phải trỏ đúng definition. |
| workflow\_versions | Versioned immutable executable configuration. | id, definition\_id, version\_no, status, revision, checksum, execution\_package\_json, based\_on\_version\_id, rollback\_of\_version\_id, created/published metadata, lock\_version | UNIQUE(definition\_id, version\_no); one active DRAFT và one current PUBLISHED theo policy. |
| workflow\_nodes | NodeDefinition normalized cho Builder/validation. | id, workflow\_version\_id, node\_key, node\_type, name, description, config\_schema\_version, config\_json, input\_schema\_json, output\_schema\_json, position\_json | UNIQUE(version\_id,node\_key); composite identity hỗ trợ FK Edge cùng version. |
| workflow\_edges | Source of truth duy nhất cho destination/routing. | id, workflow\_version\_id, source\_node\_id, source\_port, target\_node\_id, condition\_json, priority, is\_default, transition\_type, label, config\_json | Index(version,source\_node,source\_port,priority); FK source/target phải thuộc cùng version. |
| workflow\_forms | TicketForm/TaskForm schema versioned. | id, workflow\_version\_id, form\_key, form\_type, schema\_json, schema\_checksum | UNIQUE(version\_id,form\_key). Published form immutable. |
| workflow\_variables | VariableDefinition typed. | id, workflow\_version\_id, key, type, scope, default\_json, mutable, sensitive | UNIQUE(version\_id,key). |

WorkflowVersion nên giữ cả normalized rows và execution\_package\_json. Normalized tables phục vụ Draft editing, validation, semantic diff và dependency analysis; execution\_package\_json là compiled canonical snapshot được checksum tại Publish và là artifact runtime có thể đọc ổn định trong nhiều năm.

-- Constraint ý tưởng
UNIQUE (definition\_id, version\_no)
UNIQUE (workflow\_version\_id, node\_key)
INDEX workflow\_edges(workflow\_version\_id, source\_node\_id, source\_port, priority)

Do workflow\_definitions.current\_published\_version\_id và workflow\_versions.definition\_id tạo quan hệ vòng, migration nên tạo bảng trước rồi ADD CONSTRAINT sau; service/publish transaction phải kiểm tra pointer version thuộc đúng definition.

## 25.8 Validation persistence

|  |  |  |
| --- | --- | --- |
| **Table** | **Cột chính** | **Ý nghĩa** |
| workflow\_validation\_runs | id, workflow\_version\_id, revision, definition\_checksum, valid, publishable, error\_count, warning\_count, info\_count, validated\_by, validated\_at | Snapshot lần Validate để audit và UI history. Publish vẫn full-validate lại server-side. |
| workflow\_validation\_issues | id, validation\_run\_id, rule\_code, severity, resource\_type, resource\_id, field\_path, message, suggestion, metadata\_json | Stable rule code + locator để FE focus đúng node/field. |

## 25.9 Ticket domain

|  |  |  |
| --- | --- | --- |
| **Table** | **Các cột chốt** | **Constraint / Semantics** |
| tickets | id, request\_type\_id, creator\_id, status, data\_json, data\_revision, current\_revision\_id, created\_at, updated\_at, submitted\_at, completed\_at, lock\_version | data\_json chỉ chứa schema-controlled Workflow Fields; không có Ticket Custom Field. |
| ticket\_revisions | id, ticket\_id, revision\_no, data\_snapshot\_json, source\_schema\_version, schema\_checksum, submitted\_by, submitted\_at, change\_reason | UNIQUE(ticket\_id,revision\_no). Revision immutable. |
| ticket\_subjects | id, ticket\_id, subject\_type, subject\_ref\_id, role\_key, source\_field, created\_at | Normalize Business Subject để permission/search/report/manager-of-subject không phải parse arbitrary JSON. |

Khi REQUEST\_REVISION làm thay đổi business data thật, hệ thống tạo TicketRevision mới. Không mutate vô hình dữ liệu đã submit. Event giữ started\_ticket\_revision\_id và execution cycle có thể tham chiếu revision mới theo rework policy.

## 25.10 Event và NodeExecution runtime

|  |  |  |
| --- | --- | --- |
| **Table** | **Các cột chốt** | **Constraint / Semantics** |
| events | id, ticket\_id, workflow\_version\_id, started\_ticket\_revision\_id, event\_type(ROOT|CHILD), status, outcome, wait\_reason, root\_event\_id, parent\_event\_id, parent\_node\_execution\_id, previous\_event\_id, restarted\_from\_event\_id, trigger\_type, trigger\_correlation\_key, variables\_json, started\_by, started\_at, ended\_at, lock\_version | Event bind immutable WorkflowVersion. Default tối đa một active ROOT Event/Ticket. |
| node\_executions | id, event\_id, node\_definition\_id, activation\_key, cycle\_id, iteration, path\_token, item\_token, split\_scope\_id, join\_scope\_id, status, wait\_reason, outcome\_port, input\_json, output\_json, error\_json, started\_ticket\_revision\_id, timestamps, lock\_version | UNIQUE(activation\_key). TUYỆT ĐỐI không UNIQUE(event\_id,node\_definition\_id). |
| participant\_snapshots | id, event\_id, node\_execution\_id, item\_execution\_id, resolver\_type, resolver\_config\_json, resolution\_status, resolved\_user\_id, participant\_role, resolved\_at, snapshot\_json | Resolution tại activation; org thay đổi sau đó không sửa snapshot active task. |

-- Active root Event
CREATE UNIQUE INDEX uq\_ticket\_active\_root\_event
ON events(ticket\_id)
WHERE event\_type = 'ROOT'
 AND status IN ('CREATED','RUNNING','WAITING');

variables\_json là event-level runtime variable store ở baseline. Mọi update phải đi qua declared VariableDefinition + explicit output mapping + optimistic locking. Nếu về sau contention do parallel branches cao, có thể tách event\_variables mà không đổi expression contract.

## 25.11 RoutingDecision và ActivationToken

Runtime đã chốt là token-driven / activation-driven, không DFS/BFS execution. Vì vậy RoutingDecision và ActivationToken phải có persistence rõ ràng, không chỉ tồn tại trong memory.

|  |  |  |
| --- | --- | --- |
| **Table** | **Các cột chốt** | **Constraint / Semantics** |
| routing\_decisions | id, event\_id, source\_node\_execution\_id, outcome\_port, routing\_mode, evaluated\_json, selected\_json, decided\_at | UNIQUE(source\_node\_execution\_id) vì một NodeExecution terminal chỉ route một lần. |
| activation\_tokens | id, event\_id, source\_node\_execution\_id, edge\_id, target\_node\_definition\_id, activation\_key, path\_token, cycle\_id, item\_token, split\_scope\_id, join\_scope\_id, status, created\_at, consumed\_at | UNIQUE(activation\_key). Retry worker không tạo duplicate downstream NodeExecution. |

activationKey ≈
 eventId + sourceExecutionId + edgeId + pathToken + cycleId + itemToken

Root START activation cũng phải có stable activation key riêng để duplicate trigger/retry không tạo hai START executions.

## 25.12 Human Task database

|  |  |  |
| --- | --- | --- |
| **Table** | **Các cột chốt** | **Semantics** |
| task\_executions | id, node\_execution\_id, item\_execution\_id nullable, status, outcome, assignee\_id, title\_snapshot, description\_snapshot, form\_schema\_json, priority, due\_at, created\_at, started\_at, completed\_at, lock\_version | TaskStatus và TaskOutcome tách riêng. REJECT = COMPLETED + outcome REJECTED. |
| task\_candidates | task\_id, user\_id, source\_type, source\_snapshot\_json, created\_at | PK(task\_id,user\_id). Dùng cho CLAIMABLE\_POOL. |
| task\_assignment\_history | id, task\_id, action\_type, from\_user\_id, to\_user\_id, actor\_id, reason, metadata\_json, created\_at | Append-only ASSIGN/CLAIM/UNCLAIM/REASSIGN; giữ đường cho DELEGATE extension. |
| task\_decisions | id, task\_id, command\_id, actor\_id, principal\_id nullable, outcome, form\_data\_json, comment, created\_at | Idempotent terminal human decision; audit actor/principal rõ. |

Không pre-create toàn bộ Task khi Event start. Task chỉ sinh khi Human Node/Item activate. Task terminal không reopen; rework tạo TaskExecution mới.

## 25.13 Multi-instance / ItemExecution

|  |  |  |
| --- | --- | --- |
| **Table** | **Các cột chốt** | **Semantics** |
| node\_item\_executions | id, node\_execution\_id, item\_token, item\_key, ordinal, input\_json, output\_json, status, outcome, started\_at, completed\_at, lock\_version | UNIQUE(node\_execution\_id,item\_token). Mỗi runtime item có output/correlation riêng. |
| multi\_instance\_states | node\_execution\_id PK, total\_items, completed\_items, successful\_items, completion\_policy\_json, remaining\_item\_policy, status, lock\_version | Aggregate row để ANY/N\_OF\_M/PERCENTAGE quyết định threshold an toàn khi concurrent. |

Khi threshold đạt, remainingItemPolicy=CANCEL\_REMAINING hoặc KEEP\_RUNNING phải được snapshot. KEEP\_RUNNING không được route downstream lần thứ hai.

## 25.14 Parallel Split / Join persistence

|  |  |  |
| --- | --- | --- |
| **Table** | **Các cột chốt** | **Semantics** |
| join\_states | id, event\_id, join\_node\_definition\_id, join\_node\_execution\_id nullable, split\_execution\_id, join\_scope\_id, cycle\_id, item\_token nullable, policy, required\_count, remaining\_branch\_policy, expected\_tokens, arrived\_tokens, status, lock\_version | Aggregate theo split scope; không đếm nodeId chung chung. |
| join\_arrivals | join\_state\_id, activation\_token\_id, path\_token, arrived\_at | UNIQUE(join\_state\_id,activation\_token\_id); mỗi token chỉ arrive một lần. |

Join aggregation là một trong số ít nơi được phép short pessimistic/row lock để serialize decision. Nested parallel dựa vào splitExecutionId/joinScopeId/pathToken để không consume nhầm sibling token.

## 25.15 Rework / Runtime Requested Fields

|  |  |  |
| --- | --- | --- |
| **Table** | **Các cột chốt** | **Semantics** |
| revision\_requests | id, event\_id, source\_task\_id, requested\_by, target\_node\_id, cycle\_id, comment, status, created\_at, completed\_at | REQUEST\_REVISION instance cho Ticket/Event hiện tại. |
| revision\_requested\_fields | id, revision\_request\_id, field\_key, label, type, required, ordinal, sensitive, schema\_json | Runtime field schema; P1 giới hạn TEXT/TEXTAREA/NUMBER/DATE/DATETIME/SELECT/BOOLEAN/FILE/FILE\_LIST. |
| revision\_requested\_values | requested\_field\_id PK/FK, value\_json, submitted\_by, submitted\_at | Tách definition/value để requirement đã yêu cầu không bị silently mutate. |

Không có Ticket Custom Field. Runtime Requested Field chỉ áp dụng revision hiện tại và không trở thành static Workflow Input Contract.

## 25.16 Integration Catalog và execution history

|  |  |  |
| --- | --- | --- |
| **Table** | **Các cột chốt** | **Semantics** |
| connector\_definitions | id, key, name, connector\_type, handler\_key, status, config\_json, credential\_ref, created\_at, updated\_at | UNIQUE(key). config\_json không chứa secret value. |
| connector\_actions | id, connector\_id, action\_key, name, status | UNIQUE(connector\_id,action\_key). |
| connector\_action\_versions | id, connector\_action\_id, version\_no, input\_schema\_json, output\_schema\_json, execution\_config\_json, retry\_policy\_json, idempotency\_policy\_json, error\_mapping\_json, permission\_policy\_json, created\_at | UNIQUE(action,version\_no). Published node pin exact action version. |
| integration\_executions | id, event\_id, node\_execution\_id, connector\_action\_version\_id, status, idempotency\_key, external\_request\_id, callback\_correlation\_id, final\_error\_code, final\_error\_json, created\_at, completed\_at, lock\_version | UNIQUE(idempotency\_key); callbackCorrelation opaque/unique. |
| integration\_attempts | id, integration\_execution\_id, attempt\_no, status, request\_masked\_json, response\_masked\_json, error\_code, error\_json, started\_at, ended\_at | UNIQUE(integration\_execution\_id,attempt\_no). Không update attempt counter làm mất lịch sử. |
| integration\_callbacks | id, integration\_execution\_id, external\_event\_id, callback\_correlation\_id, signature\_status, payload\_masked\_json, status, received\_at, processed\_at | Duplicate/late callback được record idempotently; terminal runtime không resume. |

External call transaction boundary: TX1 persist integration attempt/running → COMMIT; gọi external ngoài transaction; TX2 persist result + activation job → COMMIT.

## 25.17 SLA, Notification và File

|  |  |  |
| --- | --- | --- |
| **Table** | **Các cột chốt** | **Semantics** |
| business\_calendars | id, key, name, timezone, status | UNIQUE(key). |
| business\_calendar\_hours | calendar\_id, day\_of\_week, start\_time, end\_time | Working windows. |
| business\_calendar\_holidays | id, calendar\_id, holiday\_date, name, working\_override | Holiday/override. |
| sla\_executions | id, event\_id, node\_execution\_id nullable, task\_id nullable, business\_calendar\_id, config\_snapshot\_json, status, started\_at, due\_at, breached\_at, completed\_at, next\_action\_at | due\_at snapshot tại activation; calendar đổi không silently recalc. |
| notification\_dispatches | id, event\_id, node\_execution\_id, task\_id, channel, recipient\_user\_id, recipient\_snapshot\_json, template\_snapshot\_json, payload\_json, dedup\_key, status, attempts, sent\_at, last\_error\_json | UNIQUE(dedup\_key) để retry không gửi trùng. |
| files | id, original\_name, mime\_type, size\_bytes, checksum, storage\_provider, bucket, storage\_key, scan\_status, uploaded\_by, uploaded\_at, retention\_until, metadata\_json | Bytes ở object storage; UNIQUE(storage\_key). |
| file\_links | id, file\_id, owner\_type, owner\_id, field\_key, created\_at | Correlation permission/retention về TicketRevision/Task/RevisionRequest. |

Ticket/Task JSON chỉ giữ FILE\_REF/fileId; tuyệt đối không lưu binary/base64 trong EventContext hoặc JSONB business payload.

## 25.18 Organization & Personnel database

|  |  |  |
| --- | --- | --- |
| **Table** | **Các cột chốt** | **Constraint / Semantics** |
| employees | id, user\_id, employee\_code, status, created\_at, updated\_at | UNIQUE(user\_id), UNIQUE(employee\_code). Không duplicate auth credentials. |
| organization\_units | id, code, name, type, parent\_unit\_id, manager\_position\_id, status, effective\_from, effective\_to | Organization Tree: “thuộc đâu?”. Không lưu managerUserId trực tiếp. |
| organization\_unit\_closure | ancestor\_unit\_id, descendant\_unit\_id, depth | PK(ancestor,descendant); indexes ancestor/depth và descendant/depth. |
| positions | id, code, name, org\_unit\_id, reports\_to\_position\_id, status, level | Reporting Tree: “báo cáo cho ai?”. Không cycle/self-report. |
| position\_closure | ancestor\_position\_id, descendant\_position\_id, depth | PK(ancestor,descendant); lookup manager N cấp/subordinates nhanh. |
| position\_assignments | id, employee\_id, position\_id, is\_primary, assignment\_type, effective\_from, effective\_to, status, created\_at | Một active primary assignment/employee tại một thời điểm. |
| reporting\_relations | id, subordinate\_employee\_id, manager\_employee\_id, relation\_type, priority, effective\_from, effective\_to, status | Optional override/matrix/project/temporary; không phá Position tree. |

CREATE UNIQUE INDEX uq\_employee\_active\_primary\_position
ON position\_assignments(employee\_id)
WHERE is\_primary = true
 AND effective\_to IS NULL
 AND status = 'ACTIVE';

Closure tables là read optimization có tính domain: OrganizationResolver dùng chúng để getNthManager/getSubordinates/isManagerOf nhanh. Mọi cập nhật cây phải duy trì closure atomically và validate không cycle trước commit.

## 25.19 Command Idempotency, Jobs, Outbox và Audit

|  |  |  |
| --- | --- | --- |
| **Table** | **Các cột chốt** | **Semantics** |
| command\_executions | id, command\_id, scope\_type, scope\_id, command\_type, actor\_id, expected\_version, status, result\_json, error\_json, created\_at, completed\_at | UNIQUE(scope\_type,scope\_id,command\_id). Duplicate successful command trả original result khi có thể. |
| workflow\_jobs | id, job\_type, aggregate\_type, aggregate\_id, payload\_json, status, attempts, max\_attempts, next\_run\_at, lease\_owner, lease\_until, dedup\_key, last\_error\_json, timestamps | UNIQUE(dedup\_key); worker claim bằng lease; DEAD visible operationally. |
| outbox\_events | id, event\_type, aggregate\_type, aggregate\_id, payload\_json, status, created\_at, published\_at, attempts, last\_error\_json | Persist cùng business TX; publisher xử lý after commit. |
| audit\_events | id, aggregate\_type, aggregate\_id, event\_type, actor\_id, principal\_id, correlation\_id, command\_id, metadata\_json, occurred\_at | Append-only; index aggregate timeline và event\_type/time. |

-- Worker claim pattern
SELECT id
FROM workflow\_jobs
WHERE status IN ('READY','RETRY')
 AND next\_run\_at <= now()
ORDER BY next\_run\_at, created\_at
FOR UPDATE SKIP LOCKED
LIMIT :batch;

Operational failures ban đầu có thể là VIEW hợp nhất workflow\_jobs DEAD + integration/node/event FAILED. Chỉ tạo operational\_incidents riêng khi Operator cần lifecycle/case-management độc lập.

## 25.20 Search/Reporting cho dynamic Ticket data

Không tạo database column/Flyway migration mỗi lần Workflow Owner thêm Workflow Field. Core giữ tickets.data\_json JSONB. Chỉ field được đánh dấu filterable/reportable mới cần projection/index khi có nhu cầu truy vấn thực tế.

|  |  |  |
| --- | --- | --- |
| **Table optional** | **Cột** | **Mục đích** |
| ticket\_search\_values | ticket\_id, field\_key, value\_type, text\_value, number\_value, boolean\_value, timestamp\_value, reference\_value | Typed projection cho query amount > X, department=Y, subject/reference... mà không scan JSONB. |

Không blanket GIN index mọi JSONB. GIN tăng write/storage cost; chỉ index query pattern có số liệu thực tế. Builder config JSON gần như không cần GIN.

## 25.21 Indexes bắt buộc và hot-path access

|  |  |
| --- | --- |
| **Nhóm** | **Indexes chốt** |
| Ticket | tickets(status,creator\_id); tickets(request\_type\_id,status) |
| Event | events(ticket\_id); events(status,started\_at); events(workflow\_version\_id); partial unique active root/Ticket |
| Node | node\_executions(event\_id,status); node\_executions(event\_id,node\_definition\_id); UNIQUE(activation\_key) |
| Task | task\_executions(assignee\_id,status,due\_at); task\_executions(node\_execution\_id) |
| Routing | activation\_tokens(status,created\_at); UNIQUE(activation\_key); workflow\_edges(version,source\_node,source\_port,priority) |
| Integration | integration\_executions(node\_execution\_id); UNIQUE(idempotency\_key); UNIQUE(callback\_correlation\_id) |
| Jobs | workflow\_jobs(status,next\_run\_at); UNIQUE(dedup\_key); lease indexes tùy worker query |
| Audit | audit\_events(aggregate\_type,aggregate\_id,occurred\_at); audit\_events(event\_type,occurred\_at) |
| Organization | position\_assignments(employee\_id/position\_id); closure ancestor/depth + descendant/depth |

## 25.22 FK, immutability và delete policy

* Published/Superseded/Archived WorkflowVersion và child node/edge/form/variable rows không được UPDATE/DELETE. Enforce service layer và nên có DB trigger/guard để tránh repository bug sửa published artifact.
* Runtime core không hard-delete khi còn audit/reference. Archive/retention là explicit process; không cascade từ WorkflowDefinition xuống Event history.
* FK node/edge phải bảo đảm sourceNode và targetNode thuộc cùng WorkflowVersion; nên dùng composite FK hoặc validation + DB composite unique.
* Integration/Attachment/Audit retention có thể khác Event retention; file bytes object storage có lifecycle riêng nhưng file metadata/reference phải preserve theo policy.
* Late callback/command không được revive terminal Event/NodeExecution; DB state guard + optimistic version là source of truth cuối.

## 25.23 Concurrency và transaction boundary theo table

|  |  |
| --- | --- |
| **Operation** | **Lock/Transaction khuyến nghị** |
| Save Draft graph | Optimistic revision/lock\_version. Stale expectedRevision → HTTP 409. |
| Complete single Task | Lock Task row/update version; aggregate NodeExecution khi cần. |
| Multi-instance threshold | Short row lock multi\_instance\_states; task/item completion idempotent. |
| Join arrival | Insert join\_arrival idempotent + short row lock join\_states để quyết định một lần. |
| Routing | NodeExecution terminal + RoutingDecision + ActivationToken/job phải atomic hoặc idempotently chained. |
| External API | Không giữ DB transaction khi network call. TX1 state/attempt; external call; TX2 result/job. |
| Cancel vs callback | Terminal transition thắng. Callback sau terminal chỉ audit LATE; không resume. |
| Worker | Lease + FOR UPDATE SKIP LOCKED; expired lease có thể claim lại; handler/job phải idempotent. |

## 25.24 Migration plan theo phase

|  |  |
| --- | --- |
| **Phase** | **Tables ưu tiên** |
| P0 – Foundation | request\_types, workflow\_definitions, workflow\_versions, workflow\_nodes, workflow\_edges, workflow\_forms, workflow\_variables, workflow\_validation\_runs/issues, tickets, ticket\_revisions, ticket\_subjects, events, node\_executions, participant\_snapshots, task\_executions, task\_candidates, task\_assignment\_history, task\_decisions, command\_executions, audit\_events |
| P1 – Runtime Dynamics | node\_item\_executions, multi\_instance\_states, routing\_decisions, activation\_tokens, join\_states, join\_arrivals, revision\_requests, revision\_requested\_fields, revision\_requested\_values, business\_calendars/hours/holidays, sla\_executions, organization\_units/closure, positions/closure, position\_assignments, optional reporting\_relations |
| P2 – Integration & Reliability | connector\_definitions, connector\_actions, connector\_action\_versions, integration\_executions, integration\_attempts, integration\_callbacks, notification\_dispatches, files, file\_links, workflow\_jobs, outbox\_events |
| P3+ – Scale/Governance | ticket\_search\_values/projections, advanced DLQ/incident tables, delegation, compensation orchestration, retention/anonymization support tables khi requirement phát sinh |

Migration phải theo expand/contract trên codebase MVP hiện tại. Không rewrite schema một lần. Legacy status/field có thể giữ adapter/backfill cho đến khi runtime mới ổn định.

## 25.25 Database invariants – CHỐT

|  |  |
| --- | --- |
| **#** | **Invariant** |
| 1 | Published configuration immutable; runtime Event luôn tham chiếu exact WorkflowVersion. |
| 2 | Runtime decision/history append-only ở các object terminal/audit; rework tạo occurrence mới. |
| 3 | Không reuse terminal NodeExecution/TaskExecution. |
| 4 | Không UNIQUE(event\_id,node\_definition\_id); logical occurrence uniqueness nằm ở activationKey/path/cycle/item correlation. |
| 5 | Dynamic config/data dùng JSONB nhưng state/correlation/permission/index-critical data phải relational. |
| 6 | Mọi mutable concurrent aggregate có lock\_version; row lock chỉ dùng ở critical aggregation. |
| 7 | Command, activation, integration, notification, job đều có idempotency/dedup identity phù hợp. |
| 8 | External/async continuation production-safe bằng durable job/outbox; không dựa vào in-memory continuation. |
| 9 | Organization hierarchy dùng OrganizationUnit tree + Position reporting tree + closure tables; workflow không query hierarchy ad-hoc. |
| 10 | File content ở object storage; DB chỉ giữ FILE\_REF metadata/link. |
| 11 | TIMESTAMPTZ/UTC cho runtime timestamp; business timezone nằm ở calendar/presentation context. |
| 12 | Không tạo DB column mới theo từng dynamic Workflow Field; reporting projection chỉ tạo khi có nhu cầu thực tế. |

KẾT LUẬN CHỐT: Physical database design này là baseline chính thức cho ERD, PostgreSQL DDL/Flyway migrations và Java persistence model. Nếu implementation cần thay đổi một invariant ở trên, phải ghi ADR/decision record mới thay vì thay đổi ngầm.

# 26. API Contract khuyến nghị

## 26.1 Request Catalog/Ticket

|  |
| --- |
| GET /api/v1/request-types  GET /api/v1/request-types/{key}/create-schema  POST /api/v1/tickets/drafts  PUT /api/v1/tickets/{id}/draft (expectedRevision)  POST /api/v1/tickets/{id}/submit (commandId, sourceSchemaVersion)  GET /api/v1/tickets/{id}  POST /api/v1/tickets/{id}/cancel |

## 26.2 Workflow Definition

|  |
| --- |
| POST /api/v1/workflows  POST /api/v1/workflows/{id}/draft  PUT /api/v1/workflows/{id}/versions/{versionId}/graph  POST /api/v1/workflows/{id}/versions/{versionId}/validate  POST /api/v1/workflows/{id}/versions/{versionId}/simulate  POST /api/v1/workflows/{id}/versions/{versionId}/publish  GET /api/v1/workflows/{id}/versions/{a}/diff/{b} |

## 26.3 Task commands

|  |
| --- |
| POST /tasks/{id}/claim  POST /tasks/{id}/complete  POST /tasks/{id}/approve  POST /tasks/{id}/reject  POST /tasks/{id}/request-revision  POST /tasks/{id}/reassign |

## 26.4 Operational

|  |
| --- |
| POST /events/{id}/terminate  POST /node-executions/{id}/retry  POST /integrations/callback/{connectorKey}  GET /operations/failures  POST /operations/jobs/{id}/retry |

## 26.5 API rules

* Actor identity từ session/token, không nhận actorId tùy ý.
* State-changing commands nhận commandId và expected version khi phù hợp.
* 409 cho stale concurrent write/invalid state conflict.
* Validation API trả stable rule codes + resource/field locator.
* Không endpoint generic setStatus.

# 27. Backend module/component architecture

|  |  |
| --- | --- |
| **Module** | **Components** |
| workflow-definition | DefinitionService, VersionService, ExecutionPackageBuilder, ValidationService, DiffService |
| workflow-runtime | EventRuntimeEngine, NodeActivationService, RoutingService, JoinService, MultiInstanceService, RuntimeCommandService |
| node-types | NodeTypeRegistry, NodeTypeProviders/Handlers/Validators |
| task | TaskService, TaskAuthorizationService, TaskCompletionAggregator, AssignmentHistoryService |
| form | FormSchemaService, TicketFormValidationService, RevisionRequestService |
| organization | OrganizationResolver, hierarchy maintenance, closure rebuild/validation |
| resolver | ParticipantResolverRegistry, ExpressionEngine, VariableResolver |
| integration | ConnectorRegistry, IntegrationService, RetryWorker, CallbackCorrelationService |
| subworkflow | SubWorkflowService |
| sla-notification | SLAService, BusinessCalendarService, NotificationService |
| operations | JobWorker, OutboxPublisher, RecoveryService, Audit/Monitoring |

## 27.1 Runtime execution result contract

|  |
| --- |
| NodeExecutionResult =  COMPLETE(output, outcomePort)  WAIT(waitDescriptor)  FAIL(error)  Handler never activates target node directly. |

## 27.2 Transaction boundaries

* Critical state transition = short transaction.
* No transaction waits human/external.
* External call outside long transaction.
* Node completion + activation job/outbox atomic or idempotently recoverable.
* Routing/Join completion protected by lock/idempotency.

# 28. Frontend Workflow Builder & Runtime UI

## 28.1 Builder layout

* Left palette: Node Catalog.
* Center: React Flow canvas.
* Right: Properties Panel generated from NodeTypeManifest/config schema/UI descriptor.
* Toolbar: Save Draft, Validate, Simulate, Diff/History, Publish.
* Validation panel: errors/warnings clickable to focus node/field.

## 28.2 Node Properties Panel

Approval panel có General, Input, Assignee, Task Generation, Form, Decision Policy, SLA, Output, Failure. System Action có Connector/Action Version, Mapping, Retry, Failure. Join có Join policy/scope/remaining branch policy.

## 28.3 Form Builder

Workflow Owner/Editor add/delete/change Workflow Fields trong Draft; Ticket Creator chỉ nhập value. Builder hiển thị field dependencies trước breaking change. Runtime Requested Fields được tạo qua Request Revision UI, không qua Ticket Creator arbitrary add-field.

## 28.4 Organization/participant UX

Participant source hiển thị friendly options như Creator’s manager, manager N levels up, Department Head, Subject’s manager; backend config compile về generic MANAGER\_OF / HEAD\_OF\_UNIT primitives.

## 28.5 Runtime UI

End user chủ yếu quản lý Ticket/Event: details, current task, timeline, assignee, due date, revision request, attachments, child process links. Full workflow graph chỉ hiển thị khi role/permission phù hợp.

# 29. Security, Privacy, Retention và Data Handling

## 29.1 Security principles

* Server-side authorization mandatory.
* Connector secrets không nằm WorkflowDefinition/EventContext/log.
* Generic HTTP connector chỉ admin-configured allowlisted host/method/auth.
* Sensitive field metadata được dùng để mask UI/audit/log/search.
* File access theo Ticket/Event visibility.
* Callback signature/replay protection.

## 29.2 Retention

Không hard-code một retention cho mọi artifact. RetentionPolicy có thể khác cho Audit, Event, Task Submission, Integration payload, Attachment, Notification. Artifact có runtime/audit reference không hard-delete trước policy; có thể anonymize/mask payload theo compliance.

## 29.3 Search/reporting dynamic fields

Workflow Field có metadata filterable/reportable/searchable. P0 dùng JSONB; field quan trọng có thể có JSON expression index hoặc projection table. Không tạo DB column/migration mỗi khi Owner thêm form field.

# 30. Test Strategy & Acceptance Criteria

## 30.1 Unit tests

* Node config validators + schema migration.
* Expression type/path validation.
* Participant/OrganizationResolver including N-level manager/head/vacant/inactive.
* Routing priority/default/ports.
* Decision aggregation/multi-instance threshold.
* Rework cycle/max iterations.
* Join token scope.
* Version/package immutability.

## 30.2 Integration/concurrency tests

* Double approve concurrent → downstream đúng một lần.
* Hai task cuối ALL complete concurrent → node/downstream đúng một lần.
* Cancel vs callback race → terminal winner preserved; late callback audited.
* Worker crash after external side effect → idempotency prevents duplicate when supported.
* Sub-workflow version changes before/after child activation.
* Workflow publish while old Events running → old Events unchanged.
* Inactive assignee recovery/audit.
* Routing no-match/ambiguous conditions handled deterministically.

## 30.3 Acceptance criteria “dynamic”

* Cùng WorkflowVersion chạy hai Ticket và resolve different manager/participants theo org runtime.
* Workflow Owner thêm Workflow Field bằng config, không đổi backend code.
* Approval outcome routes qua ports/edges, không handler hard-code target.
* REQUEST\_REVISION tạo cycle mới + optional runtime requested fields.
* Multi-instance collection size thay đổi runtime mà không sửa graph.
* Connector action đổi contract v2 không làm old Published workflow chạy theo v3.

# 31. Roadmap triển khai

|  |  |  |
| --- | --- | --- |
| **Phase** | **Scope chốt** | **Exit criteria** |
| P0 – Foundation | Domain/versioning, RequestType/TicketForm, Draft/Publish, basic graph, Node config contract/registry, Human Task, EventContext/type system, condition, basic participant + Organization core, commands, optimistic locking, audit. | Có thể build/publish/run sequential approval workflows an toàn. |
| P1 – Runtime Dynamics | Multi-instance, parallel/join, controlled rework + Runtime Requested Fields, basic BusinessCalendar/SLA, advanced completion/decision policies, organization closure queries, monitoring detail. | Các quy trình nội bộ nhiều người/branch/revision chạy ổn. |
| P2 – Integration & Durability | Connector Registry + action version, retry/idempotency, async callback, Sub-workflow, durable jobs/outbox + lease recovery, attachment model integration. | External/system workflows có recovery production-safe. |
| P3 – Operational/Governance | DLQ/recovery console, semantic diff, maker-checker optional, delegation, advanced observability, retention tooling, reporting projections, advanced calendar/performance. | Operational support/enterprise governance hoàn chỉnh. |
| P4 – Optional Extensions | Compensation orchestration nâng cao, N\_OF\_M join, reusable form libraries/templates, additional connectors/policies. | Mở theo nhu cầu thực tế, không phá core semantics. |

# 32. Anti-pattern và implementation rules bắt buộc

* Không if(workflowName == "...") trong runtime.
* Không giant NodeConfig với hàng chục nullable fields cho mọi node type.
* Không giant switch NodeType trong engine; dùng registry/provider.
* Không để NodeHandler gọi next node.
* Không lưu routing target trùng trong node config và EdgeDefinition.
* Không dùng TaskStatus.REJECTED thay cho outcome.
* Không mutate completed NodeExecution để rework; tạo occurrence mới.
* Không unique(eventId,nodeDefinitionId).
* Không resolve participant một lần khi Event start cho tất cả node.
* Không silent reassign khi org thay đổi.
* Không cho Ticket Creator thêm arbitrary field.
* Không dùng mutable latest form/connector contract cho Published version.
* Không external call trong long transaction.
* Không blind retry non-idempotent external action.
* Không raw SQL/arbitrary URL/secrets trong workflow config.
* Không DFS/BFS toàn graph để “chạy workflow”. Runtime dùng activation/token state transitions.
* Không treat Cancel như distributed rollback.
* Không ignore unknown config field.
* Không dùng local datetime mơ hồ để persist runtime time.

# 33. Checklist & Definition of Done

## 33.1 Trước migration/domain

* Map legacy entities/status vào Ticket/Event/NodeExecution/TaskExecution.
* Thêm workflowVersionId bắt buộc cho new Event.
* Thiết kế optimistic lock + command idempotency.
* Chốt canonical type enums và execution package snapshot fields.
* Chốt OrganizationUnit/Position/Assignment/closure schema nếu dynamic manager là P0/P1 requirement.

## 33.2 Trước Runtime Engine

* NodeTypeRegistry + manifests/handlers/validators.
* RoutingService + Edge ports/routingMode/RoutingDecision.
* Activation identity/path/cycle/item token.
* Task generation + aggregation.
* Participant/OrganizationResolver snapshot semantics.
* Join token scope + multi-instance aggregation.
* Failure/retry/cancel/late-event semantics.

## 33.3 Trước Builder

* Strict config JSON schema per NodeType.
* Dynamic Ticket/Task Form Builder.
* Field dependency analysis.
* Participant source UI.
* Routing ports/edge condition editor.
* Validation issue locator.
* Subworkflow/integration mapping editors.

## 33.4 Definition of Done cho mỗi capability

1. Domain/schema + Flyway migration.

2. DTO/config schema + backward compatibility.

3. Backend handler/service.

4. Authorization/state guards.

5. Idempotency/concurrency strategy.

6. Audit/monitoring events.

7. Frontend configuration/runtime UI.

8. Validation rules.

9. Unit/integration/concurrency tests.

10. Operational recovery behavior.

## 33.5 Requirement change rule

|  |
| --- |
| **QUY TẮC ƯU TIÊN:** Khi requirement mới xuất hiện: (1) thử biểu diễn bằng primitive/config hiện có; (2) nếu không đủ, thêm generic Policy/Resolver/Connector/NodeType; (3) chỉ tạo business-specific special case khi có ADR chứng minh không thể generalize an toàn. |

# PHỤ LỤC A – JSON cấu hình mẫu khái niệm

## A.1 Approval Node

|  |
| --- |
| {  "key": "managerApproval",  "type": "APPROVAL",  "configSchemaVersion": 1,  "input": {  "bindings": [{  "target": "request",  "expression": "${ticket.data}",  "expectedType": "OBJECT",  "required": true,  "onMissing": "ERROR"  }]  },  "participant": {  "resolver": {  "sourceType": "MANAGER\_OF",  "subject": {"type": "TICKET\_CREATOR"},  "level": 1,  "relationType": "PRIMARY"  },  "fallback": [  {"sourceType": "HEAD\_OF\_UNIT", "subject": {"type":"TICKET\_CREATOR"}, "unitType":"DEPARTMENT"}  ]  },  "task": {  "generationStrategy": "DIRECT\_SINGLE",  "allowedActions": ["APPROVE","REJECT","REQUEST\_REVISION"],  "decisionAggregationPolicy": "ALL\_APPROVE",  "rejectBehavior": "FAIL\_FAST"  },  "output": {"schema": {"decision":"STRING","comment":"STRING"}}  }  Edges:  managerApproval.APPROVED -> financeReview  managerApproval.REJECTED -> endRejected  managerApproval.REVISION\_REQUESTED -> requesterRevision [transitionType=REWORK] |

## A.2 Multi-instance Evaluation

|  |
| --- |
| {  "key": "employeeSelfEvaluation",  "type": "REVIEW",  "execution": {"mode":"MULTI\_INSTANCE"},  "multiInstance": {  "collection": "${ticket.data.evaluationTargets}",  "itemVariable": "employee",  "executionMode": "PARALLEL",  "subject": "${employee}",  "completionPolicy": {"type":"ALL"},  "remainingItemPolicy": "CANCEL\_REMAINING"  },  "participant": {  "resolver": {"sourceType":"EXPRESSION","expression":"${item.employee}"}  },  "task": {"generationStrategy":"DIRECT\_SINGLE"}  } |

## A.3 System Action

|  |
| --- |
| {  "key": "createPurchaseOrder",  "type": "SYSTEM\_ACTION",  "configSchemaVersion": 1,  "typeConfig": {  "connectorKey": "ERP",  "actionKey": "CREATE\_PURCHASE\_ORDER",  "actionVersion": 2,  "executionMode": "SYNC"  },  "failure": {  "retry": {"maxAttempts":3,"backoff":"EXPONENTIAL"},  "afterRetryExhausted": "CREATE\_MANUAL\_TASK"  }  } |

# PHỤ LỤC B – State/Outcome Summary

|  |  |  |
| --- | --- | --- |
| **Aggregate** | **States** | **Outcome/Port** |
| Event | CREATED, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED, TERMINATED | APPROVED/REJECTED/SUCCESS/... |
| NodeExecution | CREATED, READY, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED, SKIPPED | APPROVED/REJECTED/SUCCESS/ERROR/FIRED/... |
| TaskExecution | READY, CLAIMED, IN\_PROGRESS, COMPLETED, CANCELLED, EXPIRED | APPROVED/REJECTED/RETURNED/SUBMITTED/... |
| IntegrationExecution | CREATED/RUNNING/WAITING\_CALLBACK/SUCCEEDED/FAILED/CANCELLED | Mapped connector result/error |

# PHỤ LỤC C – Runtime pseudocode chốt

|  |
| --- |
| completeNode(execution, result):  lock + state guard  persist output + outcomePort  apply explicit variable mappings  create routing decision / activation jobs idempotently  audit  commit  route(sourceExecution):  assert source COMPLETED and Event non-terminal  edges = outgoing(sourceNode, sourceExecution.outcomePort)  matches = evaluate(edges, current EventContext)  selected = routingMode.select(matches, priority, default)  create ActivationToken(s) with path/cycle/item correlation  activate(token):  idempotency guard by activationKey  create new NodeExecution occurrence  resolve typed input snapshot  resolve participant snapshot if needed  dispatch NodeHandler  persist WAIT / COMPLETE / FAIL |

# PHỤ LỤC D – Decision Log v2.1

|  |  |
| --- | --- |
| **Nhóm** | **Quyết định chốt v2.1** |
| Node Configuration | Typed strict contract, NodeTypeManifest, configSchemaVersion, stable node.key. |
| Routing | Port + Edge là source of truth; Runtime token-driven, không DFS execution. |
| Forms | No Ticket Custom Fields; Workflow Owner controls schema; Runtime Requested Fields cho revision. |
| Validation | Multi-phase compiler/static analysis; Publish revalidates revision/checksum. |
| Rework | Controlled cycle với explicit REWORK edge, maxIterations, exhaustion behavior, new occurrences. |
| Ticket Creation | User chọn Request Type; Event bind current Published version tại submit/start. |
| Organization | Tách Organization tree và Position reporting tree; closure tables + OrganizationResolver. |
| Participants | Generic MANAGER\_OF/HEAD\_OF\_UNIT; resolve at activation + snapshot. |
| Integration | Pin connector action version; secrets runtime refs; callback correlation/replay protection. |
| Reliability | Terminal-wins race semantics; durable jobs/outbox P2; operational recovery. |
| Files | FILE\_REF/object storage; permission/scan/retention. |
| Versioning | Immutable execution package; semantic diff; rollback tạo monotonic new version. |

# KẾT LUẬN KIẾN TRÚC

Specification v2.1 là baseline đủ để triển khai physical ERD/Flyway, Java aggregates/services/handlers, Routing/Activation engine, OrganizationResolver, REST contracts, React Flow node configuration schema, validation compiler và test suite. Các thay đổi sau bản này nên được đánh giá như một Architecture Decision mới thay vì thay đổi ngầm semantics đã publish.

|  |
| --- |
| **BASELINE STATUS:** Core đã đóng: Definition/Version, RequestType/Ticket/Event, Node Configuration, Dynamic Form, Organization/Participant, Human Task, Multi-instance, Routing/Join, Rework, Sub-workflow, Integration, Validation, Lifecycle, Concurrency và Operational baseline. |

## D.13 Physical Database Design baseline – chốt v2.3

PostgreSQL Hybrid Relational + JSONB; normalized Definition rows + immutable execution\_package\_json; runtime token/activation persistence; multi-instance ItemExecution; JoinState; command idempotency; Connector Action Versioning; Organization closure tables; durable job/outbox. Các invariant tại mục 25.25 là normative.

# PHỤ LỤC E – CHI TIẾT KỸ THUẬT BẢO LƯU ĐẦY ĐỦ TỪ BASELINE v2.0

Phụ lục này được đưa vào bản FULL EXPANDED để bảo đảm không mất bất kỳ mức chi tiết nào của bản v2.0. Nội dung bên dưới được bảo lưu gần như nguyên vẹn từ phần kiến trúc chi tiết của v2.0. Khi một thuật ngữ hoặc semantics đã được chốt lại ở phần chính v2.2/v2.1 (ví dụ Node Configuration, routing theo output port/EdgeDefinition, Request Type/Ticket Form, controlled rework, Organization/Reporting Tree), phần chính có hiệu lực ưu tiên; các ví dụ và chi tiết triển khai không xung đột vẫn là normative guidance.

Mục đích của Phụ lục E là giữ đầy đủ các chi tiết mà bản v2.1 trước đó đã cô đọng lại, đặc biệt: Dynamic Trigger, EventContext/Variable, participant sources, form rules, human-task lifecycle, multi-instance, parallel/join, SLA, sub-workflow, integration, Ticket/Event lifecycle, command authorization, concurrency/idempotency, versioning, visibility, monitoring/audit, schema, API, backend/frontend architecture, validation, hai kịch bản end-to-end, test strategy, roadmap, anti-pattern và checklist triển khai.

1. Tầm nhìn, kiến trúc tổng thể và boundary

2. Domain model và quan hệ Definition–Ticket–Event

3. Dynamic Trigger và Workflow Selection

4. EventContext, Variable, Input/Output Mapping và Expression Engine

5. Dynamic Participant Engine và Business Subject

6. Dynamic Form Engine

7. Human Task Runtime, Assignment và Completion Policy

8. Multi-instance / Dynamic Fan-out

9. Graph Runtime, Condition, Parallel/Join và Loop/Rework

10. Dynamic SLA, Escalation và Notification

11. Dynamic Sub-workflow – semantics chốt

12. Dynamic System Action / Integration – semantics chốt

13. Ticket & Event Lifecycle

14. Command/State Transition và Authorization

15. Concurrency, Idempotency và Crash Recovery

16. Publish/Version/Change Management

17. Permission, Visibility và Organization Directory

18. Runtime Monitoring, Audit và Observability

19. Mô hình dữ liệu và schema khuyến nghị

20. API Contract khuyến nghị

21. Backend module/component architecture

22. Frontend Workflow Builder & Runtime UI

23. Validation, Publish Gate và Simulation

24. Hai kịch bản chuẩn end-to-end

25. Test Strategy và Acceptance Criteria

26. Roadmap triển khai theo phase

27. Anti-pattern và implementation rules bắt buộc

28. Checklist dùng tài liệu này để code

## 1. Tầm nhìn, kiến trúc tổng thể và boundary

Workflow Platform phải cho phép doanh nghiệp thay đổi phần lớn quy trình bằng cấu hình thay vì sửa source code. “Động” không chỉ là kéo thả node; nó bao gồm việc cùng một WorkflowVersion có thể chạy với dữ liệu khác nhau, người tham gia khác nhau, số lượng task khác nhau, đường đi khác nhau và integration output khác nhau mà engine không cần biết tên nghiệp vụ cụ thể.

### 1.1 Architecture principle: Definition-driven execution

|  |
| --- |
| WORKFLOW DEFINITION / VERSION  ├─ Trigger Definition  ├─ Variable Definition  ├─ Form Schema  ├─ Node Definition  │ ├─ Participant Rule  │ ├─ Assignment Strategy  │ ├─ Completion Policy  │ ├─ Input Mapping  │ ├─ Output Schema  │ ├─ SLA / Failure Strategy  │ └─ Reject / Return Action  └─ Edge / Condition  │ publish  ▼  IMMUTABLE VERSION  │ trigger/start  ▼  EVENT  ├─ EventContext  ├─ Variable Snapshot  ├─ Participant Snapshot  ├─ NodeExecution 0..N  │ └─ TaskExecution 0..N  ├─ IntegrationExecution 0..N  ├─ SLAExecution 0..N  └─ AuditEvent 0..N |

### 1.2 Bốn lớp “động”

| **Lớp** | **Khả năng** | **Mức ưu tiên** |
| --- | --- | --- |
| Dynamic Graph | Nodes/edges/conditions, branch, parallel, join, return/loop có guard. | P0 |
| Dynamic Business | Participant, form, approval policy, SLA, notification, visibility. | P0 |
| Dynamic Runtime | Context, variable, input/output mapping, multi-instance, wait/resume, retry. | P0/P1 |
| Dynamic Integration | Connector/action, webhook, async callback, sub-workflow, external events. | P1/P2 |

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Core phải hoàn chỉnh ba lớp đầu; lớp Integration phải có extension point từ đầu nhưng không mở arbitrary scripting/SQL. |

### 1.3 Node handler architecture

Runtime Engine không dùng một khối switch/if lớn chứa logic nghiệp vụ theo workflow. Mỗi node type là capability generic được đăng ký qua handler/registry. Engine chỉ chịu trách nhiệm state machine, context, routing, persistence, locking, audit và dispatch handler.

|  |
| --- |
| interface NodeHandler {  NodeType supports();  NodeExecutionResult execute(NodeExecutionContext ctx); }  Registry:  START -> StartNodeHandler  APPROVAL -> ApprovalNodeHandler  REVIEW -> ReviewNodeHandler  ASSIGNMENT -> AssignmentNodeHandler  CONDITION -> ConditionNodeHandler  PARALLEL\_SPLIT -> ParallelSplitHandler  JOIN -> JoinHandler  NOTIFICATION -> NotificationNodeHandler  SYSTEM\_ACTION -> SystemActionNodeHandler  SUB\_WORKFLOW -> SubWorkflowNodeHandler  END -> EndNodeHandler |

## 2. Domain model và quan hệ Definition–Ticket–Event

### 2.1 Design-time aggregate

|  |
| --- |
| WorkflowDefinition  └─ WorkflowVersion  ├─ TriggerDefinition  ├─ VariableDefinition[]  ├─ FormSchema[]  ├─ NodeDefinition[]  ├─ EdgeDefinition[]  ├─ ParticipantRule[]  └─ Policy / Metadata |

### 2.2 Runtime/business aggregate

|  |
| --- |
| Ticket  ├─ business data  ├─ business subjects/targets  └─ Event 0..N  ├─ bound WorkflowVersion  ├─ EventContext  ├─ NodeExecution 0..N  │ └─ TaskExecution 0..N  ├─ ParticipantSnapshot 0..N  ├─ IntegrationExecution 0..N  ├─ SLAExecution 0..N  └─ AuditEvent 0..N |

### 2.3 Ticket và Event không phải một object

| **Khía cạnh** | **Ticket** | **Event** |
| --- | --- | --- |
| Bản chất | Business case/request | Runtime execution |
| Version | Không bind engine version | Bind chính xác WorkflowVersion |
| Dữ liệu | Business input và subject | Runtime context/output/state |
| Lifecycle | DRAFT/SUBMITTED/IN\_PROGRESS/... | CREATED/RUNNING/WAITING/... |
| Reopen | Có thể tạo vòng xử lý mới | Terminal Event không reopen |
| Số lượng | Một business case | 0..N lịch sử; mặc định tối đa 1 active primary Event |

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Ticket là request/case. Event là execution. Không đổi lại thuật ngữ WorkflowInstance ở UI nghiệp vụ; trong code có thể giữ tên legacy nếu migration cần, nhưng semantics phải theo Event. |

### 2.4 NodeDefinition ≠ NodeExecution

Một NodeDefinition có thể được thực thi nhiều lần: do reject quay lại bước trước, do loop/rework, do multi-instance collection hoặc do participant-specific execution. Vì vậy không được đặt unique(eventId, nodeDefinitionId). Mỗi occurrence cần identity riêng và iteration/path correlation.

|  |
| --- |
| NodeDefinition: MANAGER\_REVIEW  Event E100:  NodeExecution #12 node=MANAGER\_REVIEW iteration=1 COMPLETED  ... reject/return ...  NodeExecution #25 node=MANAGER\_REVIEW iteration=2 RUNNING |

### 2.5 TaskExecution cardinality

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** NodeExecution 1 → 0..N TaskExecution. System/condition nodes có thể có 0 task; human node có thể tạo 1 hoặc nhiều task tùy resolver và multi-instance policy. |

## 3. Dynamic Trigger và Workflow Selection

Người dùng nghiệp vụ không nên cần biết “workflow kỹ thuật nào” nếu có thể chọn loại Ticket/Request Type. Trigger Resolver hoặc Workflow Selection Policy quyết định WorkflowDefinition được sử dụng.

### 3.1 Trigger types

| **Type** | **Ý nghĩa** | **Pha khuyến nghị** |
| --- | --- | --- |
| MANUAL | User tạo/submits Ticket hoặc explicit start. | P0 |
| TICKET\_CREATED | Auto start khi Ticket thỏa rule. | P0 |
| API | External API start. | P1 |
| WEBHOOK / DOMAIN\_EVENT | External event start. | P1 |
| SCHEDULE | Theo lịch. | P2 |

### 3.2 Workflow selection rule

|  |
| --- |
| Input Ticket:  type = PURCHASE  department = IT  amount = 150\_000\_000  Selection:  WHEN type == PURCHASE AND amount >= 100\_000\_000  THEN WorkflowDefinition = PURCHASE\_LARGE\_AMOUNT |

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Default UX: user chọn Request Type / business form; system resolve workflow. Direct workflow selection chỉ dành cho role có quyền hoặc use case manual đặc biệt. |

### 3.3 Trigger idempotency

Mỗi trigger có thể nhận idempotency/correlation key. Duplicate trigger không tạo duplicate Event. Nếu key đã xử lý, hệ thống trả lại Event hiện có hoặc ignore theo policy; audit vẫn có thể ghi nhận duplicate attempt.

## 4. EventContext, Variable, Input/Output Mapping và Expression Engine

### 4.1 Context namespaces chốt

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Tách tối thiểu 3 nhóm dữ liệu: Ticket Data, Event Variables và Node Output. Không dùng Ticket JSON như một “global mutable bag”. |

|  |
| --- |
| EventContext = {  ticket: { id, creatorId, type, data, subjects },  event: { id, versionId, startedAt, ... },  creator: {...},  organization: {...},  variables: {...},  nodes: {  managerReview: { executions: [...], latestOutput: {...} },  financeCheck: { latestOutput: {...} }  },  runtime: { currentNode, currentTask, participant, item } } |

### 4.2 VariableDefinition

| **Field** | **Ý nghĩa** |
| --- | --- |
| key | Tên kỹ thuật duy nhất trong workflow version. |
| type | STRING/NUMBER/BOOLEAN/DATE/DATETIME/MONEY/USER/USER\_LIST/OBJECT/ARRAY... |
| defaultValue | Literal hoặc expression. |
| scope | EVENT mặc định; có thể mở rộng NODE/MULTI\_INSTANCE\_ITEM. |
| mutable | Có được output mapping cập nhật hay không. |
| sensitive | Mask trong monitoring/log nếu true. |

|  |
| --- |
| VariableDefinition {  key: "riskLevel",  type: "STRING",  defaultValue: "LOW",  scope: "EVENT",  mutable: true }  Usage: ${variables.riskLevel} |

### 4.3 Input Mapping

Mỗi node nhận input đã được resolve theo schema thay vì đọc tùy ý toàn context trong handler. Việc này giúp type validation, test, replay và tránh coupling.

|  |
| --- |
| System Action input mapping:  employeeId = ${ticket.data.employeeId}  score = ${nodes.managerReview.latestOutput.score}  Resolved node input: {  "employeeId": "U123",  "score": 8 } |

### 4.4 Output Mapping và output schema

Node handler trả output theo output schema. Engine persist output vào NodeExecution và expose qua namespace nodes.<key>. Không nên để handler tự sửa tùy ý mọi phần của EventContext.

### 4.5 Expression Engine

| **Khả năng** | **Yêu cầu** |
| --- | --- |
| Operators | EQ/NE/GT/GTE/LT/LTE/IN/CONTAINS/IS\_NULL/NOT\_NULL theo type. |
| Logic | AND/OR, nested group. |
| References | ticket.\*, variables.\*, nodes.\*, runtime.item.\*, creator.\*, organization.\* theo whitelist. |
| Functions | Chỉ deterministic, safe: size, any, all, sum, avg, min, max, date compare... |
| Safety | Không eval JavaScript/Groovy/SpEL tùy ý từ user. |
| Validation | Compile/type-check ở publish; runtime null policy rõ. |
| Audit | Lưu expression config và route result. |

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Expression engine phải sandboxed, deterministic và type-aware. Không dùng arbitrary code execution để đạt “dynamic”. |

## 5. Dynamic Participant Engine và Business Subject

### 5.1 Nguyên lý

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Definition quyết định “cách tìm người”; runtime quyết định “người thực tế là ai”. Participant/assignee mặc định resolve khi node activate rồi snapshot kết quả cho execution đó. |

|  |
| --- |
| Design time:  ParticipantRule = REQUEST\_CREATOR\_MANAGER  Runtime:  Ticket.creator = A  A.managerId = M  Resolver -> M  ParticipantSnapshot -> M  Task.assignee -> M |

### 5.2 Participant source types

| **Source** | **Ví dụ** | **Runtime behavior** |
| --- | --- | --- |
| FIXED\_USER | U123 | Trả user cố định nếu còn hợp lệ. |
| ROLE | FINANCE\_LEAD | Resolve theo role + scope/policy. |
| GROUP | IT\_SUPPORT | Resolve members theo group policy. |
| CREATOR | ticket.creatorId | Trả người tạo Ticket. |
| CREATOR\_MANAGER | managerOf(ticket.creatorId) | Tra org directory tại activation. |
| DEPARTMENT\_HEAD | headOf(ticket.departmentId) | Tra org directory. |
| REQUEST\_FIELD | ticket.data.evaluationTargets | Lấy USER/USER\_LIST từ business input. |
| PREVIOUS\_NODE\_USER | previous.actorId | Dùng actor bước trước. |
| NODE\_OUTPUT | nodes.lookup.output.ownerId | Lấy user từ node trước. |
| FORM\_FIELD\_USER | task.formData.reviewer | User do form runtime cung cấp. |
| EXPRESSION | ${variables.finalApprover} | Resolve từ context expression. |
| CUSTOM\_RESOLVER | plugin key | Extension point được platform đăng ký. |

### 5.3 Request-field participant

|  |
| --- |
| Ticket.data = {  evaluationTargets: ["B","C","D","E","F"] }  Node ParticipantRule:  sourceType = REQUEST\_FIELD  sourcePath = ticket.data.evaluationTargets  cardinality = MULTIPLE |

Frontend chỉ cho phép chọn các user hợp lệ theo eligibility rule. Backend phải validate lại scope; không tin arbitrary userId từ client.

### 5.4 Snapshot policy khi participant thay đổi giữa runtime

Nếu A.manager là B khi node activate, task được assign B. Sau đó tổ chức đổi manager A thành C, task đang active vẫn thuộc B. Node khác activate sau thời điểm thay đổi sẽ resolve lại và có thể assign C. Điều này bảo đảm audit và tránh silent reassignment.

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Không tự re-resolve/reassign task đã active khi Organization Directory thay đổi. Mọi reassignment phải là command explicit và lưu assignment history. |

### 5.5 Missing participant / fallback chain

|  |
| --- |
| Primary: CREATOR\_MANAGER Fallback1: DEPARTMENT\_HEAD Fallback2: WORKFLOW\_OWNER OnMissing: CREATE\_MANUAL\_TASK or FAIL\_NODE |

### 5.6 Participant khác Business Subject

| **Case** | **Business Subject** | **Participant/Assignee** |
| --- | --- | --- |
| Manager A đánh giá B,C,D,E,F trên một form | B..F | A |
| Mỗi nhân viên tự đánh giá | B..F | B..F tương ứng |
| HR duyệt kết quả của B | B | HR reviewer |
| Mua tài sản cho employee B | B hoặc asset request | Manager/Finance/IT tùy node |

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Không dùng “participant” để lưu tất cả user liên quan business. Subject/target là dữ liệu nghiệp vụ; participant là người có vai trò runtime trong process. |

## 6. Dynamic Form Engine

### 6.1 Hai cấp form

* Ticket Form: form nhập business data khi tạo/submit Ticket.
* Task Form: form riêng của Approval/Review/Assignment node; mỗi bước có thể yêu cầu dữ liệu khác nhau.

### 6.2 Field types P0

| **Type** | **Mục đích** |
| --- | --- |
| TEXT | Dynamic form primitive |
| TEXTAREA | Dynamic form primitive |
| NUMBER | Dynamic form primitive |
| MONEY | Dynamic form primitive |
| DATE | Dynamic form primitive |
| DATETIME | Dynamic form primitive |
| SELECT | Dynamic form primitive |
| MULTI\_SELECT | Dynamic form primitive |
| BOOLEAN | Dynamic form primitive |
| USER | Dynamic form primitive |
| USER\_MULTI | Dynamic form primitive |
| DEPARTMENT | Dynamic form primitive |
| FILE | Dynamic form primitive |

### 6.3 Form field schema

|  |
| --- |
| {  "fieldId": "evaluationTargets",  "label": "Employees",  "type": "USER\_MULTI",  "required": true,  "visibleWhen": null,  "requiredWhen": null,  "readOnlyWhen": null,  "eligibilityRule": "sameDepartmentOrChildOrg",  "outputPath": "ticket.data.evaluationTargets" } |

### 6.4 Dynamic visibility/required/read-only

|  |
| --- |
| Field: rejectReason visibleWhen = ${runtime.decision == "REJECT"} requiredWhen = ${runtime.decision == "REJECT"} readOnlyWhen = false |

### 6.5 Form versioning

Form schema là một phần của WorkflowVersion hoặc tham chiếu versioned form artifact. Published Event phải render form theo version đã bind, không theo draft mới nhất. Submitted form data phải persist cùng form schema/version reference và input snapshot để audit.

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Form schema là configuration, có stable fieldId, type validation và output mapping. Label có thể đổi nhưng fieldId không được thay âm thầm khi còn binding phụ thuộc. |

## 7. Human Task Runtime, Assignment và Completion Policy

### 7.1 Task sinh khi node activate

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Không pre-create toàn bộ task khi Event start. Task chỉ được sinh khi node thực sự activate; nhánh không đi qua thì không có task. |

### 7.2 TaskDefinition config

|  |
| --- |
| HumanTaskConfig {  titleTemplate  descriptionTemplate  participantRule / assigneeResolver  formSchemaRef  assignmentMode  completionPolicy  priorityRule  dueDateRule  allowedCommands  rejectAction } |

### 7.3 Assignment mode

| **Mode** | **Semantics** |
| --- | --- |
| SINGLE | Resolve một assignee và tạo một task. |
| ANY\_ONE | N candidate; một người claim/complete là đủ. |
| ALL | Tạo task cho tất cả; tất cả phải complete/approve theo policy. |
| SEQUENTIAL | Tạo/activate lần lượt theo thứ tự đã resolve. |
| TASK\_PER\_USER | Mỗi user/item có task riêng, thường dùng multi-instance/fan-out. |

### 7.4 Completion policy

| **Policy** | **Điều kiện complete NodeExecution** |
| --- | --- |
| ALL | Tất cả task required đạt trạng thái success. |
| ANY | Một task đạt success; các task còn lại cancel/obsolete theo policy. |
| MAJORITY | Số approve > 50% hoặc rule cụ thể. |
| N\_OF\_M | Đủ N trên M. |
| PERCENTAGE | Đủ phần trăm threshold. |
| SEQUENTIAL\_ALL | Tất cả theo thứ tự. |
| CUSTOM\_SAFE\_RULE | Expression trên task aggregate, không arbitrary code. |

### 7.5 Task lifecycle

| **State** | **Ý nghĩa** |
| --- | --- |
| CREATED | Record được tạo. |
| READY | Có thể xử lý. |
| CLAIMED | Candidate đã claim. |
| IN\_PROGRESS | Đang làm. |
| COMPLETED | Hoàn thành không mang semantics reject. |
| REJECTED | Decision reject. |
| CANCELLED | Bị hủy do Event/path/ANY policy/reassignment rule. |
| EXPIRED | Quá hạn và policy đánh dấu expired. |

### 7.6 Task output

Task submission phải tạo immutable submission/decision record hoặc history entry, cập nhật task terminal state, và phát internal runtime event để NodeExecution aggregate đánh giá completion. Không để controller tự gọi next node.

## 8. Multi-instance / Dynamic Fan-out

### 8.1 Mục đích

Multi-instance cho phép một NodeDefinition chạy theo một collection có kích thước chỉ biết tại runtime. Đây là capability cốt lõi cho các case như đánh giá N nhân viên, duyệt N tài liệu, kiểm tra N tài sản hoặc gửi N task song song mà không tạo sẵn N branch trong graph.

|  |
| --- |
| MultiInstanceConfig {  collection: ${ticket.data.evaluationTargets}  itemVariable: employee  executionMode: PARALLEL | SEQUENTIAL  assignee: ${employee.id} // hoặc ${ticket.creatorId}  subject: ${employee}  completionPolicy: ALL | ANY | N\_OF\_M | PERCENTAGE } |

### 8.2 Ba case bắt buộc support

| **Case** | **Collection item** | **Assignee** | **Số task** |
| --- | --- | --- | --- |
| A điền một form tổng cho 5 nhân viên | N/A | A | 1 |
| A đánh giá từng nhân viên riêng | employee | A | 5 |
| Mỗi nhân viên tự đánh giá | employee | employee | 5 |

### 8.3 Runtime model

|  |
| --- |
| NodeExecution parent (multi-instance)  ├─ ItemExecution employee=B -> Task assignee=A or B  ├─ ItemExecution employee=C -> Task assignee=A or C  ├─ ItemExecution employee=D -> Task ...  └─ Aggregate completion policy |

Tên entity vật lý có thể là NodeExecution child hoặc MultiInstanceItemExecution; điều bắt buộc là correlation item/participant rõ, không đánh mất output của từng item và không ép một Task chứa nhiều subject không phân biệt.

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Multi-instance là extension của NodeExecution, không tạo Event mới cho từng item. Sub-workflow mới là process boundary tạo Child Event riêng. |

## 9. Graph Runtime, Condition, Parallel/Join và Loop/Rework

### 9.1 Edge/connection

| **Field** | **Ý nghĩa** |
| --- | --- |
| sourceNodeId | Node nguồn. |
| sourcePort | Approved/Rejected/Default/... |
| targetNodeId | Node đích. |
| condition | Safe expression tùy chọn. |
| priority | Thứ tự evaluate. |
| isDefault | Fallback nếu không condition match. |
| label | Nhãn business-readable. |

### 9.2 Parallel split

|  |
| --- |
| Manager Approved  │  ▼  PARALLEL\_SPLIT  ┌──────┴──────┐  ▼ ▼ Finance Review Legal Review  │ │  └──────┬──────┘  ▼  JOIN  ▼  Director |

### 9.3 Join policy P0

| **Policy** | **Semantics** |
| --- | --- |
| ALL | Chờ tất cả required incoming branch/token. |
| ANY | Một branch đủ điều kiện thì tiếp; branch còn lại xử lý cancellation/late arrival theo policy. |

P1 có thể mở N\_OF\_M. Join phải track token/path identity; không dùng “đếm NodeExecution completed tổng” theo node id vì loop/multi-instance có thể tạo nhiều occurrences.

### 9.4 Reject / Return / Request Revision

| **RejectAction** | **Semantics** |
| --- | --- |
| END\_REJECTED | Kết thúc Event với outcome REJECTED. |
| RETURN\_TO\_REQUESTER | Tạo node/task revision cho requester. |
| RETURN\_TO\_PREVIOUS | Route về execution point trước theo policy. |
| GOTO\_NODE | Route đến NodeDefinition chỉ định. |
| REQUEST\_REVISION | Semantic business; thường compile thành GOTO\_NODE + revision metadata. |

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Quay lại node đã complete phải tạo NodeExecution occurrence mới; không đổi execution cũ từ COMPLETED về RUNNING. |

### 9.5 Loop guard

Loop/rework được phép nhưng cần iteration counter, maxIterations hoặc operational warning. Engine phải chống accidental infinite loop do graph config. Publish validation detect direct cycle và yêu cầu explicit loop/rework flag nếu cycle được cho phép.

## 10. Dynamic SLA, Escalation và Notification

### 10.1 SLA config

| **Field** | **Ví dụ** |
| --- | --- |
| dueDuration | 4 giờ / 2 working days |
| calendarRef | VN\_WORKING\_CALENDAR |
| timezone | Asia/Ho\_Chi\_Minh |
| reminders | T-2h, T+1h |
| escalationResolver | managerOf(currentAssignee) |
| timeoutAction | AUTO\_REJECT / GOTO\_NODE / CREATE\_MANUAL\_TASK / FAIL\_NODE |

### 10.2 SLA snapshot

Khi task/node activate, dueAt phải được tính và lưu snapshot. Thay đổi calendar/rule sau đó không âm thầm sửa dueAt đang active trừ khi có explicit recalculate command/policy.

### 10.3 Notification

Notification recipient được resolve từ cùng Participant/Resolver infrastructure. Template sử dụng EventContext. Mỗi dispatch có dedup key để worker retry không gửi lặp. Notification failure không mặc định fail business Event; severity/failure policy phải configurable.

## 11. Dynamic Sub-workflow – semantics chốt

### 11.1 Định nghĩa

Sub-workflow dùng khi một bước thực chất là một business process độc lập có nhiều node, task, SLA, participant và history riêng. Parent Event tạo một Child Event; không “inline” toàn bộ runtime state của child vào parent.

### 11.2 Parent/Child relation

|  |
| --- |
| Parent Event E001 (Purchase Workflow V2)  └─ SubWorkflow NodeExecution N15  └─ Child Event E002 (Vendor Verification V4)  parentEventId = E001  parentNodeExecutionId = N15  rootEventId = E001 |

### 11.3 Version resolution – QUYẾT ĐỊNH CUỐI CÙNG

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Sub-workflow CHƯA chạy thì khi SubWorkflow Node activate sẽ resolve CURRENT PUBLISHED version mới nhất tại thời điểm đó. Ngay khi Child Event được tạo, Child Event bind version đó và giữ nguyên đến hết; publish version mới sau đó không ảnh hưởng Child Event đang chạy. |

|  |
| --- |
| Ngày 1: Parent Event E001 start; child current = V3; sub-node chưa activate Ngày 2: publish child V4 Ngày 3: sub-node activate -> resolve V4 -> create Child Event E002 bind V4 Ngày 4: publish child V5 -> E002 vẫn V4 Ngày 5: parent khác mới activate -> child mới bind V5 |

Nếu child WorkflowDefinition đang SUSPENDED/ARCHIVED hoặc không có Published version tại activation, không được chạy Draft. Apply sub-workflow failure strategy.

### 11.4 Execution modes

| **Mode** | **Behavior** |
| --- | --- |
| WAIT\_FOR\_COMPLETION | Parent SubWorkflow NodeExecution chuyển WAITING; child terminal rồi parent map result và tiếp. |
| FIRE\_AND\_CONTINUE | Tạo Child Event và parent tiếp ngay; child chạy độc lập, correlation vẫn được lưu. |

### 11.5 Input/Output contract

|  |
| --- |
| Parent -> Child input mapping:  vendorId = ${ticket.data.vendorId}  purchaseAmount = ${ticket.data.amount}  Child InputSchema:  vendorId: STRING required  purchaseAmount: MONEY  Child output:  riskLevel: STRING  compliancePassed: BOOLEAN  Parent node output:  ${nodes.vendorVerification.latestOutput.riskLevel} |

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Child không được mutate parent EventContext tùy ý. Giao tiếp qua explicit input/output contract. Nếu cần update business record external/shared, dùng System Action rõ ràng. |

### 11.6 Child terminal mapping

| **Child result** | **Parent policy** |
| --- | --- |
| COMPLETED | Map output -> complete parent node. |
| REJECTED | Reject parent / GOTO\_NODE / manual review theo config. |
| FAILED | Retry start child nếu safe / manual task / fail parent node. |
| CANCELLED | Fail/route/cancel parent theo explicit policy. |
| TERMINATED | Operational failure route, không giả thành business reject. |

### 11.7 Recursion

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Phase đầu không cho recursive sub-workflow trực tiếp/gián tiếp. Publish validator xây dependency graph và chặn cycle A→B→A. Có thể thêm max depth trong tương lai nhưng không phải P0. |

## 12. Dynamic System Action / Integration – semantics chốt

### 12.1 System Action khác Sub-workflow

| **Đặc điểm** | **Sub-workflow** | **System Action** |
| --- | --- | --- |
| Mục đích | Gọi business process khác | Thực hiện capability/system operation |
| Runtime object | Child Event | IntegrationExecution |
| Human task | Có thể có | Không trực tiếp |
| SLA/graph riêng | Có | Không |
| Ví dụ | Vendor Verification | ERP.CREATE\_PURCHASE\_ORDER |

### 12.2 Không biến Builder thành Postman

Workflow Owner không được nhập arbitrary URL + secret tùy ý. Platform sử dụng Connector Registry; Technical Admin đăng ký/approve connector và action. Workflow Owner chỉ chọn connector/action được cấp quyền và map input/output.

|  |
| --- |
| Connector: ERP  allowedHosts: [erp.company.internal]  credentialRef: ERP\_PROD  actions:  CREATE\_PURCHASE\_ORDER  GET\_PURCHASE\_ORDER  CANCEL\_PURCHASE\_ORDER  Workflow SystemAction Node:  connectorKey = ERP  actionKey = CREATE\_PURCHASE\_ORDER  inputMapping = {...} |

### 12.3 Connector/Action contract

| **Field** | **Yêu cầu** |
| --- | --- |
| connectorKey | Stable technical key. |
| actionKey | Stable action key. |
| inputSchema | Type + required fields. |
| outputSchema | Output contract. |
| credentialRef | Secret reference, không raw secret. |
| timeoutPolicy | Per action/connector. |
| retryPolicy | Max attempts + backoff + retryable errors. |
| idempotencyPolicy | Key strategy / external support. |
| permissions | Workflow/module/role được phép dùng. |

### 12.4 Runtime architecture

|  |
| --- |
| SystemActionNodeHandler  -> IntegrationService  -> ConnectorRegistry  -> Connector / ActionHandler  -> external or internal capability  Persist each attempt as IntegrationExecution / IntegrationAttempt. |

### 12.5 IntegrationExecution

| **Field** | **Ý nghĩa** |
| --- | --- |
| id | Runtime ID. |
| eventId/nodeExecutionId | Correlation. |
| connectorKey/actionKey | Capability. |
| requestSnapshot | Sanitized resolved input. |
| responseSnapshot | Sanitized output/response. |
| attemptNo | Retry attempt. |
| status | PENDING/RUNNING/SUCCEEDED/FAILED/WAITING\_CALLBACK. |
| idempotencyKey | Stable across safe retries. |
| errorCode/errorMessage | Operational troubleshooting. |
| startedAt/completedAt | Timing. |

### 12.6 Retry + timeout + idempotency

External call không nằm trong DB transaction dài. Persist intent/running state trước, commit, gọi external, rồi mở transaction mới để lưu result/transition. Retry chỉ cho error được Connector định nghĩa retryable. Retry action tạo side effect phải tái sử dụng cùng idempotency key.

|  |
| --- |
| Failure example:  Attempt 1 -> 503 -> retry after 5s  Attempt 2 -> timeout -> retry after 10s  Attempt 3 -> 200 -> SUCCEEDED  Idempotency-Key = eventId:nodeExecutionId:logicalActionId |

### 12.7 Sync và Async callback

| **Mode** | **Behavior** |
| --- | --- |
| SYNC | Call và nhận response trong thời gian ngắn; handler lưu result rồi route. |
| ASYNC\_CALLBACK | Call trả externalRequestId; NodeExecution WAITING\_EXTERNAL; webhook/event callback correlate và resume. |

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Phase đầu có thể implement SYNC trước nhưng domain/state machine phải async-ready để tránh đập lại khi tích hợp e-signing, ERP batch hoặc long-running external process. |

### 12.8 Failure strategy

| **Strategy** | **Use case** |
| --- | --- |
| RETRY | Transient technical failure. |
| FALLBACK\_ACTION | Dùng connector/action phụ. |
| CREATE\_MANUAL\_TASK | External down nhưng business vẫn cần đi tiếp bằng manual recovery. |
| GOTO\_NODE | Route sang manual/error branch. |
| CONTINUE\_WITH\_WARNING | Action non-critical. |
| FAIL\_NODE / FAIL\_EVENT | Critical technical failure. |

### 12.9 Security

* Không log Authorization/API key/password/token.
* Generic HTTP connector chỉ do Technical Admin cấu hình allowlist host/method/credential.
* Không raw SQL node. Internal capability phải qua action handler có validation/authorization.
* Publish validation kiểm tra workflow owner được phép dùng connector/action.
* Audit SYSTEM\_ACTION\_STARTED/SUCCEEDED/FAILED/RETRIED nhưng mask sensitive payload.

## 13. Ticket & Event Lifecycle

### 13.1 Ticket states

| **State** | **Ý nghĩa** |
| --- | --- |
| DRAFT | User đang nhập, chưa submit. |
| SUBMITTED | Đã gửi business request; có thể đang chờ trigger/selection. |
| IN\_PROGRESS | Có active primary Event. |
| COMPLETED | Business process hoàn tất. |
| REJECTED | Business outcome bị từ chối. |
| CANCELLED | Business case bị hủy. |

### 13.2 Event states

| **State** | **Ý nghĩa** |
| --- | --- |
| CREATED | Event record tạo và bind WorkflowVersion. |
| RUNNING | Có runnable/executing path. |
| WAITING | Không có runnable work ngay, đang chờ human/timer/external/sub-workflow. |
| COMPLETED | Technical execution hoàn thành thành công; outcome có thể lưu riêng. |
| REJECTED | Business process kết thúc bằng reject nếu model chọn status này. |
| FAILED | Technical failure chưa recover. |
| CANCELLED | Business/user cancel có thẩm quyền. |
| TERMINATED | Administrative/emergency force stop. |

### 13.3 Waiting reason

Không cần bùng nổ EventStatus. Dùng waitReason/currentWaitSummary: WAITING\_HUMAN, WAITING\_TIMER, WAITING\_EXTERNAL, WAITING\_SUBWORKFLOW hoặc nhiều wait reasons nếu parallel.

### 13.4 Event creation binding point

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Khi Event được tạo, bind current Published WorkflowVersion ngay. Từ đó Event giữ version cố định. Event creation là version-binding point của workflow chính. |

### 13.5 Cancel vs Terminate

| **Command** | **Semantics** | **Actor** |
| --- | --- | --- |
| CANCEL\_EVENT | Dừng business case hợp lệ; cancel active tasks/nodes theo propagation policy. | Creator/Owner/Manager/Admin theo policy. |
| TERMINATE\_EVENT | Force stop vận hành/incident; reason bắt buộc. | Admin/Workflow Operator. |

### 13.6 Reopen

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Không chuyển Event terminal về RUNNING. Reopen Ticket tạo Event mới. Ticket giữ Event history để audit. |

### 13.7 Retry vs Restart

| **Khái niệm** | **Semantics** |
| --- | --- |
| Retry | Retry failed execution/attempt trong SAME Event; không chạy lại workflow từ đầu. |
| Restart | Tạo NEW Event. Default bind latest Published version; có thể cho Admin chọn original version khi có nhu cầu điều tra/compliance. |
| Reopen | Business command trên Ticket dẫn đến Event mới; khác technical retry. |

### 13.8 Cardinality rule

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Một Ticket có 0..N Event lịch sử nhưng mặc định tối đa 1 active primary Event tại một thời điểm. Child Event của sub-workflow không tính như primary Ticket Event. |

## 14. Command/State Transition và Authorization

### 14.1 Command-driven state machine

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Không expose API cho client PATCH status tùy ý. Client gửi business/runtime command; server kiểm tra current state + actor permission + policy + revision/idempotency rồi tự transition. |

| **Command** | **Target** | **Guard chính** |
| --- | --- | --- |
| SUBMIT\_TICKET | Ticket | DRAFT + creator/authorized. |
| START\_EVENT | Ticket/Workflow | Submitted + workflow active + published version. |
| CLAIM\_TASK | Task | READY + actor candidate. |
| COMPLETE\_TASK | Task | READY/CLAIMED/IN\_PROGRESS + assignee. |
| APPROVE\_TASK | Approval Task | Active + approver authorization. |
| REJECT\_TASK | Approval Task | Active + approver authorization. |
| REASSIGN\_TASK | Task | Active + policy grants actor. |
| CANCEL\_EVENT | Event | Non-terminal + cancel policy. |
| TERMINATE\_EVENT | Event | Non-terminal + admin/operator. |
| RETRY\_EXECUTION | Node/Integration | FAILED + retryable. |
| FORCE\_COMPLETE\_TASK | Task | Admin/operator + reason. |
| REOPEN\_TICKET | Ticket | Terminal Ticket + policy; creates new Event. |
| RESTART\_EVENT | Historical Event/Ticket | Creates new Event; never mutate old. |

### 14.2 Approve/Reject guards

* Task phải active và chưa terminal.
* Event phải active/non-terminal.
* Actor phải đúng assignee/candidate theo current assignment snapshot.
* Command idempotency key chưa được xử lý hoặc phải trả lại cùng result cũ.
* Task revision/version phải match để chống stale UI.
* Nếu SLA auto-action đã xảy ra, command sau đó bị reject/conflict.

### 14.3 Reassign

Reassign cập nhật current assignee nhưng không xóa participant resolution snapshot ban đầu. Lưu TaskAssignmentHistory: fromAssignee, toAssignee, actor, reason, timestamp. Reassign có thể do current assignee, manager, workflow owner hoặc admin tùy policy.

### 14.4 Force complete

Force complete là operational override, không phải normal completion. Bắt buộc reason và audit event riêng TASK\_FORCE\_COMPLETED. Không dùng force complete để che bug trong runtime.

### 14.5 Transition matrix Event

| **From** | **Allowed normal transitions** |
| --- | --- |
| CREATED | RUNNING, WAITING, FAILED, CANCELLED, TERMINATED |
| RUNNING | WAITING, COMPLETED, REJECTED, FAILED, CANCELLED, TERMINATED |
| WAITING | RUNNING, COMPLETED, REJECTED, FAILED, CANCELLED, TERMINATED |
| FAILED | RUNNING qua retry/recovery, CANCELLED, TERMINATED |
| COMPLETED | Terminal – không transition |
| REJECTED | Terminal – không transition |
| CANCELLED | Terminal – không transition |
| TERMINATED | Terminal – không transition |

## 15. Concurrency, Idempotency và Crash Recovery

### 15.1 Baseline strategy

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Dùng optimistic locking làm baseline, targeted pessimistic lock/atomic update ở critical aggregate transition, command idempotency, node activation idempotency, external action idempotency và notification dedup. |

### 15.2 Optimistic locking

|  |
| --- |
| TaskExecution version = 10  UPDATE task\_execution SET status = 'COMPLETED', version = 11 WHERE id = :id AND version = 10;  rowCount = 0 -> stale/conflict/already processed |

### 15.3 Critical completion race

Ví dụ ALL policy có hai task cuối complete đồng thời. Cả hai thread không được cùng activate downstream. Khi aggregate NodeExecution đánh giá completion, lock row/compare-and-set state hoặc dùng unique activation token trong cùng transaction để chỉ một thread được chuyển WAITING/RUNNING -> COMPLETED và emit downstream activation.

### 15.4 Command idempotency

|  |
| --- |
| RuntimeCommandRecord {  commandId UUID UNIQUE,  commandType,  actorId,  targetType, targetId,  requestHash,  resultStatus, resultRef,  createdAt }  Duplicate same commandId -> return stored result, do not execute twice. |

### 15.5 Node activation idempotency

Không unique(eventId,nodeDefinitionId). Activation identity phải chứa execution/path/iteration/item token. Với join/multi-instance, key phải phản ánh logical occurrence để vừa chống duplicate vừa cho phép intentional repeated execution.

### 15.6 External idempotency

Integration retry dùng cùng logical idempotency key. Nếu external system không hỗ trợ idempotency và action non-idempotent, Connector phải đánh dấu để runtime không auto-retry mù; chuyển manual recovery hoặc reconciliation.

### 15.7 Notification dedup

|  |
| --- |
| dedupKey = eventId + nodeExecutionId + notificationRuleId + triggerType |

### 15.8 Delivery model

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Kiến trúc mục tiêu: at-least-once delivery + idempotent processing. Không tuyên bố exactly-once toàn hệ thống. |

### 15.9 Crash recovery

Worker/job có trạng thái RUNNING + startedAt + lease/heartbeat nếu cần. Scheduler tìm stale execution vượt timeout, kiểm tra idempotency và đưa về retry/recovery queue. Không tự reset mọi RUNNING khi restart server.

## 16. Publish/Version/Change Management

### 16.1 Tách status Definition và Version

| **Entity** | **States khuyến nghị** |
| --- | --- |
| WorkflowDefinition | ACTIVE / SUSPENDED / ARCHIVED |
| WorkflowVersion | DRAFT / PUBLISHED / SUPERSEDED / ARCHIVED |

Có thể giữ legacy enum khác trong code, nhưng semantics cần tương đương. SUSPENDED là availability của definition, không làm mất historical fact rằng một version đã published.

### 16.2 Draft editing

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Chỉ DRAFT editable. Published/Superseded version immutable. Muốn sửa published workflow: clone current published thành draft version mới, chỉnh, validate, publish. |

### 16.3 Một active draft

Phase đầu nên giới hạn một active Draft trên WorkflowDefinition để giảm merge/conflict. Draft save phải dùng optimistic expectedRevision; stale write trả conflict.

### 16.4 Publish

1. Run full validation.

2. Acquire definition/version publish lock.

3. Freeze snapshot/checksum.

4. Mark old current Published thành SUPERSEDED nếu cần.

5. Mark new version PUBLISHED và set currentPublishedVersionId.

6. Register/activate trigger config.

7. Write audit event.

8. Commit atomically hoặc có publish recovery policy rõ nếu external trigger registration không transactional.

### 16.5 Runtime binding

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Event mới bind currentPublishedVersionId tại creation. Event đang chạy không đổi. Sub-workflow bind current Published của child tại child activation; child đã tạo không đổi. |

### 16.6 Suspend

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Suspend WorkflowDefinition chặn Event mới/Child Event mới. Event đã chạy tiếp tục bình thường. Suspend không auto-cancel task/event đang active. |

### 16.7 Archive

Archive dùng khi workflow không còn nhận business mới. Archive ẩn khỏi active catalog, chặn new Event/draft theo policy, nhưng giữ toàn bộ version/runtime/history. Event đang chạy tiếp tục trừ khi Admin explicit terminate. Archive không đồng nghĩa delete runtime.

### 16.8 Delete

Draft chưa dùng có thể delete. Artifact đã có runtime reference không hard delete; dùng archive/soft-delete và retention policy. Foreign key/runtime reference phải bảo toàn khả năng audit.

### 16.9 Version numbering

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Published version number do system sinh. User không tự sửa version number. Có thể hiển thị sequence 1,2,3 hoặc semantic label; identity kỹ thuật luôn là workflowVersionId immutable. |

## 17. Permission, Visibility và Organization Directory

### 17.1 Definition permissions

| **Role** | **Quyền** |
| --- | --- |
| Admin | Global management, integration, runtime override. |
| Workflow Owner | Manage lifecycle/version/publish của workflow sở hữu. |
| Editor | Edit draft theo ACL. |
| Viewer | Read definition/runtime theo visibility. |
| Approver/Reviewer/Assignee | Runtime responsibility, không nhất thiết là global role. |

### 17.2 Runtime visibility policy

VisibilityResolver có thể xét creator, current participant, previous participant, creator manager, same department, workflow owner, explicit watcher, configured role và admin. Policy phải server-side; frontend chỉ là presentation.

### 17.3 Organization Directory minimum

| **Field** | **Purpose** |
| --- | --- |
| userId/externalId | Stable identity. |
| displayName/email | UI/notification. |
| active | Filter participants. |
| managerId | Manager resolver/escalation. |
| departmentId/orgUnitId | Department scope. |
| role/group membership | Role/group resolver. |
| effective timestamps optional | Audit/temporal resolution nếu cần sau này. |

### 17.4 Source of truth

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Phase đầu dùng một organization source of truth được normalize vào internal directory. User bị thiếu manager vẫn sync bình thường; resolver xử lý missing bằng fallback, không tạo fake manager. |

## 18. Runtime Monitoring, Audit và Observability

### 18.1 Event list

| **Field** | **Hiển thị** |
| --- | --- |
| Ticket/Event ID | Business/runtime correlation. |
| Workflow Name / Version | Version chính xác. |
| Current step summary | Một hoặc nhiều active nodes. |
| Assigned summary | Một hoặc nhiều assignee/tasks. |
| Event status/outcome | Runtime + business. |
| StartedAt | Start time. |
| Creator/trigger source | Ai/nguồn nào tạo. |
| SLA summary | Nearest due/overdue. |

### 18.2 Event detail

* Render graph của WorkflowVersion thực tế đã chạy; highlight NodeExecution status.
* Hiển thị multiple executions của cùng NodeDefinition theo iteration/path.
* Hiển thị child sub-workflow links và parent/root correlation.
* Hiển thị participant snapshots, assignments, reassignments và tasks.
* Timeline: trigger → node activation → task assignment → command → route → integration attempts → completion.
* Context inspector phải mask sensitive fields và có permission.

### 18.3 Audit events tối thiểu

| **Category** | **Examples** |
| --- | --- |
| Definition | WORKFLOW\_CREATED, DRAFT\_UPDATED, VALIDATED, PUBLISHED, SUSPENDED, REACTIVATED, ARCHIVED. |
| Runtime | EVENT\_CREATED, EVENT\_STARTED, NODE\_ACTIVATED, NODE\_COMPLETED, EVENT\_COMPLETED/FAILED/CANCELLED/TERMINATED. |
| Task | TASK\_CREATED, CLAIMED, APPROVED, REJECTED, COMPLETED, REASSIGNED, FORCE\_COMPLETED. |
| Integration | ACTION\_STARTED, RETRIED, SUCCEEDED, FAILED, CALLBACK\_RECEIVED. |
| SLA | REMINDER\_SENT, ESCALATED, TIMEOUT\_ACTION. |
| Sub-workflow | CHILD\_EVENT\_CREATED, CHILD\_EVENT\_COMPLETED/FAILED. |
| Security | PERMISSION\_CHANGED, OVERRIDE\_USED. |

### 18.4 Observability

* Structured logs có eventId/nodeExecutionId/taskId/integrationExecutionId/correlationId.
* Metrics: active events, waiting tasks, overdue SLA, failed integrations, retry counts, publish errors.
* Tracing cho external integration/sub-workflow chain nếu hạ tầng hỗ trợ.
* Không log full sensitive context mặc định.

## 19. Mô hình dữ liệu và schema khuyến nghị

Tên bảng chỉ là khuyến nghị; đội code có thể điều chỉnh naming. Điều bắt buộc là giữ đúng aggregate boundary, immutability và correlation semantics.

| **Table/Aggregate** | **Core columns** |
| --- | --- |
| workflow\_definitions | id, key, name, description, type, module, owner\_id, lifecycle\_status, current\_published\_version\_id, active\_draft\_version\_id, revision, created\_at, updated\_at, archived\_at |
| workflow\_versions | id, workflow\_definition\_id, version\_no, status, graph\_json/checksum, created\_by, created\_at, published\_at, superseded\_at |
| workflow\_nodes | version\_id, node\_id, node\_key, node\_type, name, config\_json, input\_schema\_json, output\_schema\_json, position\_json |
| workflow\_edges | version\_id, edge\_id, source\_node\_id, source\_port, target\_node\_id, condition\_json, priority, is\_default |
| workflow\_variables | version\_id, key, type, default\_json, mutable, sensitive |
| workflow\_forms | version\_id, form\_id, schema\_json |
| tickets | id, ticket\_code, type, creator\_id, status, business\_data\_json, subject\_data\_json, submitted\_at, completed\_at, revision |
| events | id, ticket\_id, workflow\_definition\_id, workflow\_version\_id, parent\_event\_id, parent\_node\_execution\_id, root\_event\_id, status, outcome, context\_summary\_json, started\_at, completed\_at, version |
| node\_executions | id, event\_id, node\_definition\_id/key, parent\_execution\_id, iteration, path\_token, item\_key, participant\_snapshot\_id, status, wait\_reason, input\_snapshot\_json, output\_json, started\_at, completed\_at, version |
| task\_executions | id, node\_execution\_id, task\_type, assignee\_id, status, due\_at, decision, form\_data\_json, subject\_snapshot\_json, created\_at, completed\_at, version |
| task\_assignment\_history | id, task\_id, from\_assignee\_id, to\_assignee\_id, actor\_id, reason, created\_at |
| participant\_snapshots | id, event\_id/node\_execution\_id, resolver\_type, resolver\_config\_hash, subject\_ref, resolved\_user\_id, snapshot\_json, resolved\_at |
| integration\_executions | id, event\_id, node\_execution\_id, connector\_key, action\_key, logical\_action\_id, status, idempotency\_key, request\_snapshot\_json, response\_snapshot\_json, error\_code, error\_message, started\_at, completed\_at |
| integration\_attempts | id, integration\_execution\_id, attempt\_no, status, started\_at, completed\_at, error\_code, sanitized\_response\_json |
| runtime\_commands | command\_id UNIQUE, command\_type, actor\_id, target\_type, target\_id, request\_hash, result\_json, created\_at |
| runtime\_audit\_events | id, event\_id, node\_execution\_id, task\_id, actor\_id, event\_type, payload\_summary\_json, created\_at |
| organization\_users | id, external\_id UNIQUE, display\_name, email, active, manager\_id, department\_id, metadata\_json, synced\_at |
| connectors | id/key, name, status, credential\_ref, config\_json, permissions\_json |
| connector\_actions | connector\_key, action\_key, input\_schema\_json, output\_schema\_json, retry\_policy\_json, idempotency\_policy\_json, permissions\_json |

### 19.1 Constraints quan trọng

* workflow\_versions published không update mutable graph fields; enforce ở service và có thể DB trigger/policy tùy team.
* events.workflow\_version\_id NOT NULL sau creation.
* Không unique(event\_id,node\_definition\_id).
* runtime\_commands.command\_id UNIQUE.
* integration\_executions.idempotency\_key unique theo connector/action scope nếu phù hợp.
* Task terminal transition dùng optimistic version.
* Không cascade delete historical runtime khi archive workflow.

## 20. API Contract khuyến nghị

### 20.1 Definition APIs

|  |
| --- |
| GET /api/v1/workflows POST /api/v1/workflows GET /api/v1/workflows/{id} POST /api/v1/workflows/{id}/draft PUT /api/v1/workflows/{id}/draft?expectedRevision=... POST /api/v1/workflows/{id}/validate POST /api/v1/workflows/{id}/publish POST /api/v1/workflows/{id}/suspend POST /api/v1/workflows/{id}/reactivate POST /api/v1/workflows/{id}/archive GET /api/v1/workflows/{id}/versions |

### 20.2 Ticket/Event APIs

|  |
| --- |
| POST /api/v1/tickets POST /api/v1/tickets/{id}/submit POST /api/v1/tickets/{id}/reopen GET /api/v1/tickets/{id} GET /api/v1/tickets/{id}/events  GET /api/v1/events GET /api/v1/events/{id} POST /api/v1/events/{id}/cancel POST /api/v1/events/{id}/terminate POST /api/v1/events/{id}/restart |

### 20.3 Task command APIs

|  |
| --- |
| POST /api/v1/tasks/{id}/claim POST /api/v1/tasks/{id}/complete POST /api/v1/tasks/{id}/approve POST /api/v1/tasks/{id}/reject POST /api/v1/tasks/{id}/reassign POST /api/v1/tasks/{id}/force-complete  Headers/body:  commandId: UUID  expectedVersion: number  payload/formData/comment/reason... |

### 20.4 Runtime operational APIs

|  |
| --- |
| POST /api/v1/node-executions/{id}/retry POST /api/v1/integrations/callback/{connectorKey} GET /api/v1/events/{id}/timeline GET /api/v1/events/{id}/graph GET /api/v1/events/{id}/context?view=safe |

### 20.5 API response rules

| **Case** | **HTTP recommendation** |
| --- | --- |
| Stale expectedRevision/version | 409 Conflict |
| Duplicate idempotent command | 200/202 với original result |
| Forbidden actor | 403 |
| Invalid transition | 409 hoặc 422 theo convention |
| Validation error | 400/422 với field/node error list |
| Not found | 404 |

## 21. Backend module/component architecture

|  |
| --- |
| workflow-definition  WorkflowDefinitionService  WorkflowVersionService  WorkflowValidationService  workflow-runtime  EventRuntimeEngine  NodeActivationService  RoutingService  JoinService  MultiInstanceService  RuntimeCommandService  task  TaskService  TaskAuthorizationService  TaskCompletionAggregator  resolver  ParticipantResolverRegistry  VariableResolver  ExpressionEngine  OrganizationResolver  integration  ConnectorRegistry  IntegrationService  IntegrationRetryWorker  CallbackCorrelationService  subworkflow  SubWorkflowService  sla-notification  SLAService  NotificationService  audit-monitoring  RuntimeAuditService  DefinitionAuditService  EventQueryService |

### 21.1 Transaction boundaries

* Mỗi state transition critical là transaction ngắn.
* Không giữ transaction mở khi chờ human/external callback.
* Không gọi external API trong long DB transaction.
* Activation downstream + state completion phải atomic hoặc idempotent qua outbox/job key.
* Nếu dùng event bus/outbox sau này, publish event theo transactional outbox để tránh DB commit thành công nhưng message mất.

### 21.2 Runtime execution loop conceptual

|  |
| --- |
| activate(nodeExecution):  lock/guard execution  resolve input  resolve participant/assignee if needed  handler.execute(ctx)   if WAIT:  persist WAITING and return   if COMPLETE:  persist output  evaluate outgoing edges  create downstream execution(s) idempotently  resume(internalEvent):  correlate target execution/task  authorize/idempotency guard  update runtime object  aggregate node completion  if node complete -> route downstream |

## 22. Frontend Workflow Builder & Runtime UI

### 22.1 Builder layout

| **Area** | **Chức năng** |
| --- | --- |
| Left Palette | Node types generic. |
| Canvas | React Flow graph, node status validation badges. |
| Right Properties | Config selected node/edge/trigger/form. |
| Top Toolbar | Save draft, validate, simulate, publish, version history. |
| Bottom/side validation panel | Blocking errors/warnings, click để focus node/field. |

### 22.2 Participant builder UX

* Source dropdown: Fixed User / Role / Group / Creator / Creator Manager / Department Head / Request Field / Node Output / Expression.
* Preview resolver với sample context.
* Cardinality SINGLE/MULTIPLE.
* Assignment mode và completion policy tách riêng.
* Fallback chain builder.
* Hiển thị rõ “resolved at runtime” và snapshot policy.

### 22.3 Multi-instance UX

* Toggle “Run for each item”.
* Select collection expression.
* Define item variable name.
* Choose Parallel/Sequential.
* Configure subject binding, assignee binding và completion policy.

### 22.4 Sub-workflow node UX

* Chọn child WorkflowDefinition, không chọn Draft.
* Hiển thị current published version chỉ để tham khảo; runtime sẽ resolve latest published tại activation.
* Execution mode Wait/Fire-and-continue.
* Input mapping editor + schema validation.
* Output mapping + result handling COMPLETED/REJECTED/FAILED/CANCELLED.

### 22.5 Integration UX

* Chỉ list connector/action actor được phép dùng.
* Không hiển thị raw secret.
* Mapping editor type-aware.
* Retry/failure policy UI ở mức business-safe; low-level retryable HTTP codes thuộc Connector definition.
* Test connector chỉ cho actor/role phù hợp và dùng sanitized sample data.

### 22.6 Runtime UI

End user chủ yếu quản lý Ticket/Event, không cần thấy full builder. Workflow Builder dành cho role cấp cao/owner/editor. Runtime UI hiển thị Ticket details, current task, timeline, current assignee, status, due date, child process link khi có quyền.

## 23. Validation, Publish Gate và Simulation

### 23.1 Blocking validation groups

| **Group** | **Checks** |
| --- | --- |
| Graph | Start/End, dangling edge, unreachable node theo policy, invalid port, illegal cycle. |
| Node Config | Required fields per type. |
| Form | Unique fieldId, valid type/rules/mapping. |
| Expression | Reference path exists in known schema, type/operator compatible. |
| Participant | Resolver config structurally valid, fallback config coherent. |
| Multi-instance | Collection type ARRAY/LIST, item mapping valid. |
| Join | Incoming branches/token semantics valid. |
| Sub-workflow | Child exists, not recursive, input mapping compatible; child need not be current published at draft time but publish/start rules explicit. |
| Integration | Connector/action exists/enabled, owner permitted, required inputs mapped, credentialRef configured by connector. |
| SLA | Duration/calendar/escalation config valid. |
| Version | Only draft publishable; expected revision matches. |

### 23.2 Warning-level validation

* Dynamic resolver preview trả empty nhưng runtime có thể khác.
* ANY join có branch cancellation implication.
* Non-idempotent external action với auto retry disabled.
* Loop maxIterations cao hoặc không có explicit business explanation.
* Workflow child currently suspended – có thể publish parent nhưng warning; activation sau này sẽ fail/wait theo policy.

### 23.3 Simulation

Simulation chạy trên sample context, không tạo real side effect. Connector actions phải stub/dry-run. Simulation hiển thị resolved participants, selected routes, task fan-out count, sub-workflow input mapping và potential failure/warning. Mục đích là phát hiện cấu hình sai trước publish, không thay thế integration testing.

## 24. Hai kịch bản chuẩn end-to-end

### 24.1 Employee Evaluation – 5 nhân viên được chọn lúc tạo Ticket

|  |
| --- |
| Manager A creates Ticket T001 Ticket.data.evaluationTargets = [B,C,D,E,F]  START  ↓ SELF\_EVALUATION (multi-instance)  collection = evaluationTargets  item = employee  assignee = employee.id  completion = ALL  -> 5 TaskExecution  ↓ MANAGER\_REVIEW (multi-instance)  collection = evaluationTargets  item = employee  subject = employee  assignee = ticket.creatorId (A)  -> 5 task riêng cho A, mỗi task subject khác nhau  ↓ HIGHER\_MANAGER\_APPROVAL  assignee = CREATOR\_MANAGER  resolve at activation -> X, snapshot X  reject -> GOTO MANAGER\_REVIEW (new NodeExecution iteration)  approve -> HR\_REVIEW  ↓ HR\_REVIEW  assignee = ROLE(HR\_REVIEWER)  ↓ END |

Điểm kiểm chứng: B..F là business subjects ngay từ Ticket; tại Self Evaluation họ đồng thời là participants/assignees. Ở Manager Review họ là subjects nhưng assignee đều là A. Nếu manager của A đổi trước Higher Manager node activate, resolver dùng manager mới; nếu task đã tạo thì không tự đổi.

### 24.2 Purchase Approval + Sub-workflow + ERP

|  |
| --- |
| Ticket PURCHASE amount=150M  ↓ Manager Approval  ↓ SubWorkflow: Vendor Verification  activation time -> resolve child current Published V4  create Child Event, bind V4 forever  wait for completion  output riskLevel  ↓ IF riskLevel == HIGH -> Director Pre-Review ELSE -> Parallel Split  ├─ Finance Review  └─ Legal Review  ↓ JOIN ALL SystemAction: ERP / CREATE\_PURCHASE\_ORDER  retry + idempotency + IntegrationExecution  output orderId  ↓ Notification  ↓ END |

Nếu ERP timeout sau khi đã tạo PO nhưng response mất, retry dùng cùng idempotency key. Nếu connector đánh dấu action không idempotent, không auto retry; tạo manual recovery/reconciliation task. Nếu Vendor Workflow V5 publish sau Child Event start, child vẫn chạy V4.

## 25. Test Strategy và Acceptance Criteria

### 25.1 Unit tests

* Expression operator/type/null policy.
* Participant resolvers và fallback chain.
* Assignment/completion policies ALL/ANY/N\_OF\_M/MAJORITY.
* Multi-instance expansion.
* Join token aggregation.
* Version resolution rule của main Event và sub-workflow.
* State transition guards.
* Retry classification và idempotency key generation.

### 25.2 Integration tests

* Publish immutable version và start Event bind đúng version.
* Publish new version trong khi Event cũ đang chạy – Event cũ không đổi.
* Sub-workflow chưa activate rồi publish child version mới – activation dùng version mới.
* Sub-workflow đã tạo Child Event rồi publish child version mới – child không đổi.
* Double approve concurrent – downstream activate đúng một lần.
* Hai task cuối ALL complete concurrent – join/downstream đúng một lần.
* External action worker crash sau side effect – retry không duplicate khi idempotency supported.
* Reassign giữ history và authorization.
* Cancel propagation tới active tasks/nodes.
* Reopen/restart tạo Event mới, Event cũ terminal giữ nguyên.

### 25.3 Acceptance criteria “dynamic”

* Cùng WorkflowVersion chạy hai Ticket khác nhau và resolve assignee khác nhau.
* Request field USER\_LIST có thể tạo N task mà không sửa workflow.
* Form visibility/required thay đổi theo context.
* Node output được node sau dùng qua mapping.
* Condition route khác nhau từ cùng version.
* Parallel branches chạy độc lập và join đúng policy.
* Reject quay lại tạo NodeExecution iteration mới.
* Missing participant thực thi fallback thay vì assign sai.
* Connector retry không tạo duplicate side effect khi capability hỗ trợ idempotency.
* Audit có thể giải thích version nào, ai được resolve, tại sao route nào được chọn.

## 26. Roadmap triển khai theo phase

| **Phase** | **Phạm vi** | **Exit criteria** |
| --- | --- | --- |
| P0 – Foundation | Domain/versioning, draft/publish, Ticket/Event, basic graph, human task, EventContext, condition, participant resolver, dynamic form, commands, optimistic locking, audit. | Có thể chạy approval workflow động end-to-end. |
| P1 – Runtime Dynamics | Multi-instance, parallel/join, return/rework, SLA/escalation, advanced completion policy, failure fallback, monitoring detail. | Case đánh giá nhiều nhân viên + purchase parallel hoạt động ổn định. |
| P2 – Integration | Connector Registry, System Action retry/idempotency, async-ready callback, Sub-workflow parent/child, version resolution rule. | ERP/vendor child process chạy được với audit/correlation. |
| P3 – Operational Hardening | Outbox/queue, lease recovery, advanced observability, simulation, policy builder, business calendar, performance tuning. | Production-grade scale/recovery metrics đạt SLA nội bộ. |
| P4 – Optional Extensions | Additional connectors, N\_OF\_M join, subworkflow advanced policies, reusable form libraries, templates. | Mở rộng theo nhu cầu thực tế, không phá core semantics. |

|  |
| --- |
| **QUYẾT ĐỊNH ĐÃ CHỐT:** Không implement mọi extension ngay. Tuy nhiên schema/domain P0 không được đóng đường khiến P1/P2 phải rewrite Ticket/Event/NodeExecution/TaskExecution. |

## 27. Anti-pattern và implementation rules bắt buộc

| **Anti-pattern** | **Thay bằng** |
| --- | --- |
| if(workflowName == "Purchase") trong runtime | Definition config + generic handlers. |
| 1 NodeExecution = 1 Task hard-coded | 0..N TaskExecution. |
| eventId + nodeId unique | Occurrence/path/iteration-aware identity. |
| Published workflow editable | New Draft -> Publish new version. |
| Participant = mọi user liên quan | Separate Business Subject vs Participant. |
| Resolve assignee lúc Event start cho tất cả node | Resolve tại node activation + snapshot. |
| Auto change active task assignee khi org đổi | Explicit reassign command + history. |
| Reject set old NodeExecution RUNNING again | Create new execution occurrence. |
| Raw URL/secret in System Action | Connector Registry + credentialRef. |
| Raw SQL node | Approved internal action handler. |
| External call inside long DB transaction | Persist state -> commit -> call -> persist result. |
| Exactly-once assumption | At-least-once + idempotency. |
| PATCH status from FE | Business/runtime command API. |
| Reopen Event terminal | Create new Event. |
| Sub-workflow fixed at parent Event start | Resolve child latest Published at sub-node activation; bind child thereafter. |
| Child edits parent context directly | Explicit input/output contract. |

## 28. Checklist dùng tài liệu này để code

### 28.1 Trước khi code migration/domain

* Map entity hiện tại sang thuật ngữ Ticket/Event/NodeExecution/TaskExecution.
* Xác định field nào legacy cần giữ để backward compatible.
* Chốt enum/status mapping theo semantics trong tài liệu.
* Bổ sung workflowVersionId bắt buộc cho Event.
* Bổ sung optimistic version cho runtime mutable entities.
* Thiết kế command idempotency store.

### 28.2 Trước khi code Runtime Engine

* NodeHandler registry.
* EventContext schema/resolver.
* Node activation token/occurrence identity.
* ParticipantResolver registry + snapshot.
* Task aggregate completion.
* Routing + parallel/join token semantics.
* Retry/failure API.

### 28.3 Trước khi code Builder

* JSON/schema contract cho từng node type.
* Dynamic form field schema.
* Participant/assignment/completion config schema.
* Multi-instance config schema.
* Sub-workflow config schema.
* Connector/action mapping UI contract.
* Validation error model có nodeId/fieldPath/severity/code/message.

### 28.4 Definition of Done cho mỗi capability

1. Schema/DTO + validation.

2. Persistence migration.

3. Backend service/handler.

4. Authorization + state guard.

5. Audit events.

6. Concurrency/idempotency behavior.

7. Frontend config/runtime UI.

8. Unit tests.

9. Integration/concurrency tests.

10. Monitoring/troubleshooting path.

11. Backward compatibility assessment.

### 28.5 Quy tắc ưu tiên khi requirement mới xuất hiện

Khi có requirement mới, trước tiên xác định nó là business configuration có thể biểu diễn bằng primitive hiện tại hay thực sự cần capability mới. Chỉ thêm NodeType/Resolver/Connector/Policy mới khi semantics không thể biểu diễn an toàn bằng các primitive đã có. Tránh thêm special-case trong engine để đáp ứng một workflow riêng lẻ.

## PHỤ LỤC A – Cấu hình mẫu JSON khái niệm

|  |
| --- |
| {  "workflowKey": "EMPLOYEE\_EVALUATION",  "version": 7,  "trigger": {"type": "TICKET\_CREATED"},  "variables": [  {"key":"riskLevel","type":"STRING","defaultValue":"LOW"}  ],  "nodes": [  {  "key":"selfEvaluation",  "type":"REVIEW",  "multiInstance": {  "collection":"${ticket.data.evaluationTargets}",  "itemVariable":"employee",  "executionMode":"PARALLEL",  "completionPolicy":{"type":"ALL"}  },  "assignee": {"sourceType":"EXPRESSION","value":"${employee.id}"},  "formRef":"selfEvaluationForm"  },  {  "key":"managerReview",  "type":"REVIEW",  "multiInstance": {  "collection":"${ticket.data.evaluationTargets}",  "itemVariable":"employee",  "executionMode":"PARALLEL",  "completionPolicy":{"type":"ALL"}  },  "subject":"${employee}",  "assignee":{"sourceType":"CREATOR"}  },  {  "key":"higherManager",  "type":"APPROVAL",  "assignee":{  "sourceType":"CREATOR\_MANAGER",  "fallback":["DEPARTMENT\_HEAD","WORKFLOW\_OWNER"]  },  "rejectAction":{"type":"GOTO\_NODE","target":"managerReview"}  }  ] } |

## PHỤ LỤC B – State/command rule summary

| **Object** | **Không được làm** |
| --- | --- |
| WorkflowVersion | Không edit khi PUBLISHED/SUPERSEDED. |
| Event | Không terminal -> RUNNING trực tiếp bằng API. |
| NodeExecution | Không reuse completed occurrence cho return/rework. |
| Task | Không approve/reject 2 lần; không direct status patch. |
| ParticipantSnapshot | Không silently mutate sau resolve. |
| IntegrationExecution | Không retry non-idempotent action mù. |
| Sub-workflow Child Event | Không đổi version sau creation. |

## PHỤ LỤC C – Kết luận kiến trúc

Kiến trúc đã chốt tạo ra một Workflow Platform đủ động cho các quy trình nội bộ phức tạp nhưng vẫn kiểm soát được. Sức mạnh nằm ở Definition-driven graph, EventContext, runtime resolver, dynamic form, multi-instance task, explicit completion policy, parallel/join, controlled loop/rework, connector registry, sub-workflow contract, immutable version binding và command-driven runtime. Tất cả các cơ chế đều được audit, idempotent ở nơi có thể và không phụ thuộc vào hard-code của từng quy trình.

Đây là baseline để chuyển trực tiếp sang thiết kế ERD/DB migrations, API DTO, Java aggregate/service/handler, React Flow node config schema và integration tests. Bất kỳ thay đổi foundational nào sau tài liệu này nên được ghi thành ADR mới và đánh giá impact tới compatibility/versioning/runtime history.