package com.fpt.workflow.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.connector.domain.ConnectorAction;
import com.fpt.workflow.connector.domain.ConnectorDefinition;
import com.fpt.workflow.connector.repository.ConnectorActionRepository;
import com.fpt.workflow.connector.repository.ConnectorActionVersionRepository;
import com.fpt.workflow.connector.repository.ConnectorDefinitionRepository;
import com.fpt.workflow.connector.service.ConnectorManagementService;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.publish.WorkflowPublishService;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.RequestTypeRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.definition.validation.ValidationCompilation;
import com.fpt.workflow.definition.validation.WorkflowValidationService;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.engine.FieldEditability;
import com.fpt.workflow.form.engine.FieldRequirement;
import com.fpt.workflow.form.engine.FieldSemanticMetadata;
import com.fpt.workflow.form.engine.FieldValidationRules;
import com.fpt.workflow.form.engine.FieldVisibility;
import com.fpt.workflow.form.engine.FormFieldDefinition;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.organization.domain.Employee;
import com.fpt.workflow.organization.domain.OrganizationUnit;
import com.fpt.workflow.organization.domain.Position;
import com.fpt.workflow.organization.domain.PositionAssignment;
import com.fpt.workflow.organization.repository.EmployeeRepository;
import com.fpt.workflow.organization.repository.OrganizationUnitRepository;
import com.fpt.workflow.organization.repository.PositionAssignmentRepository;
import com.fpt.workflow.organization.repository.PositionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.AuthenticatedActorPrincipal;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic, idempotent seed provider for production-grade demo workflows. Zero
 * business-specific logic in engine runtime: authoring and compiling standard workflow packages.
 */
@Service
public class DemoWorkflowSeeder {

  private static final Logger log = LoggerFactory.getLogger(DemoWorkflowSeeder.class);

  private final OrganizationUnitRepository orgUnitRepository;
  private final PositionRepository positionRepository;
  private final EmployeeRepository employeeRepository;
  private final PositionAssignmentRepository assignmentRepository;
  private final ConnectorManagementService connectorManagementService;
  private final ConnectorDefinitionRepository connectorRepository;
  private final ConnectorActionRepository actionRepository;
  private final ConnectorActionVersionRepository versionRepository;
  private final WorkflowDefinitionRepository definitionRepository;
  private final WorkflowVersionRepository workflowVersionRepository;
  private final NodeDefinitionRepository nodeRepository;
  private final EdgeDefinitionRepository edgeRepository;
  private final WorkflowFormRepository formRepository;
  private final WorkflowValidationService validationService;
  private final WorkflowPublishService publishService;
  private final RequestTypeRepository requestTypeRepository;
  private final ObjectMapper objectMapper;
  private final PlatformClock clock;

  public DemoWorkflowSeeder(
      OrganizationUnitRepository orgUnitRepository,
      PositionRepository positionRepository,
      EmployeeRepository employeeRepository,
      PositionAssignmentRepository assignmentRepository,
      ConnectorManagementService connectorManagementService,
      ConnectorDefinitionRepository connectorRepository,
      ConnectorActionRepository actionRepository,
      ConnectorActionVersionRepository versionRepository,
      WorkflowDefinitionRepository definitionRepository,
      WorkflowVersionRepository workflowVersionRepository,
      NodeDefinitionRepository nodeRepository,
      EdgeDefinitionRepository edgeRepository,
      WorkflowFormRepository formRepository,
      WorkflowValidationService validationService,
      WorkflowPublishService publishService,
      RequestTypeRepository requestTypeRepository,
      ObjectMapper objectMapper,
      PlatformClock clock) {
    this.orgUnitRepository = orgUnitRepository;
    this.positionRepository = positionRepository;
    this.employeeRepository = employeeRepository;
    this.assignmentRepository = assignmentRepository;
    this.connectorManagementService = connectorManagementService;
    this.connectorRepository = connectorRepository;
    this.actionRepository = actionRepository;
    this.versionRepository = versionRepository;
    this.definitionRepository = definitionRepository;
    this.workflowVersionRepository = workflowVersionRepository;
    this.nodeRepository = nodeRepository;
    this.edgeRepository = edgeRepository;
    this.formRepository = formRepository;
    this.validationService = validationService;
    this.publishService = publishService;
    this.requestTypeRepository = requestTypeRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public void seedAll() {
    runAsAdmin(
        () -> {
          log.info("Starting demo workflows seeding...");
          seedOrganization();
          seedConnectors();
          seedVendorVerificationWorkflow();
          seedLeaveRequestWorkflow();
          seedAccessRequestWorkflow();
          seedPurchaseRequestWorkflow();
          seedEmployeeEvaluationWorkflow();
          log.info("Demo workflows seeding completed successfully.");
        });
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 1. Organization Hierarchy Seeding
  // ────────────────────────────────────────────────────────────────────
  public void seedOrganization() {
    Instant now = clock.now();
    LocalDate effectiveDate = LocalDate.now();

    // 1. Organization Units
    seedOrgUnit(DemoIdentities.UNIT_ENG_ID, "ENG", "Engineering", null, "DEPARTMENT", now);
    seedOrgUnit(DemoIdentities.UNIT_HR_ID, "HR", "Human Resources", null, "DEPARTMENT", now);
    seedOrgUnit(DemoIdentities.UNIT_SEC_ID, "SEC", "Information Security", null, "DEPARTMENT", now);
    seedOrgUnit(DemoIdentities.UNIT_FIN_ID, "FIN", "Finance", null, "DEPARTMENT", now);
    seedOrgUnit(DemoIdentities.UNIT_LEG_ID, "LEG", "Legal & Compliance", null, "DEPARTMENT", now);

    // 2. Positions: DIR-ENG -> MGR-ENG -> STAFF-ENG
    seedPosition(
        DemoIdentities.POS_DIR_ENG_ID,
        "DIR-ENG",
        "Director of Engineering",
        DemoIdentities.UNIT_ENG_ID,
        null,
        true,
        now);
    seedPosition(
        DemoIdentities.POS_MGR_ENG_ID,
        "MGR-ENG",
        "Engineering Manager",
        DemoIdentities.UNIT_ENG_ID,
        DemoIdentities.POS_DIR_ENG_ID,
        false,
        now);
    seedPosition(
        DemoIdentities.POS_STAFF_ENG_ID,
        "STAFF-ENG",
        "Software Engineer",
        DemoIdentities.UNIT_ENG_ID,
        DemoIdentities.POS_MGR_ENG_ID,
        false,
        now);

    seedPosition(
        DemoIdentities.POS_HR_LEAD_ID,
        "LEAD-HR",
        "HR Manager",
        DemoIdentities.UNIT_HR_ID,
        null,
        true,
        now);
    seedPosition(
        DemoIdentities.POS_SEC_LEAD_ID,
        "LEAD-SEC",
        "Security Lead",
        DemoIdentities.UNIT_SEC_ID,
        null,
        true,
        now);
    seedPosition(
        DemoIdentities.POS_FIN_LEAD_ID,
        "LEAD-FIN",
        "Finance Director",
        DemoIdentities.UNIT_FIN_ID,
        null,
        true,
        now);
    seedPosition(
        DemoIdentities.POS_LEG_LEAD_ID,
        "LEAD-LEG",
        "Legal Counsel",
        DemoIdentities.UNIT_LEG_ID,
        null,
        true,
        now);

    // 3. Employees
    seedEmployee(
        DemoIdentities.ADMIN_EMPLOYEE_ID,
        DemoIdentities.ADMIN_USER_ID,
        "EMP-001",
        "System Admin",
        "admin@demo.test",
        now);
    seedEmployee(
        DemoIdentities.HR_EMPLOYEE_ID,
        DemoIdentities.HR_USER_ID,
        "EMP-002",
        "Helen Ramirez (HR)",
        "hr@demo.test",
        now);
    seedEmployee(
        DemoIdentities.SECURITY_EMPLOYEE_ID,
        DemoIdentities.SECURITY_USER_ID,
        "EMP-003",
        "Sam Vance (Security)",
        "security@demo.test",
        now);
    seedEmployee(
        DemoIdentities.FINANCE_EMPLOYEE_ID,
        DemoIdentities.FINANCE_USER_ID,
        "EMP-004",
        "Frank Miller (Finance)",
        "finance@demo.test",
        now);
    seedEmployee(
        DemoIdentities.LEGAL_EMPLOYEE_ID,
        DemoIdentities.LEGAL_USER_ID,
        "EMP-005",
        "Laura Croft (Legal)",
        "legal@demo.test",
        now);

    seedEmployee(
        DemoIdentities.DIRECTOR_EMPLOYEE_ID,
        DemoIdentities.DIRECTOR_USER_ID,
        "EMP-010",
        "David Director",
        "director@demo.test",
        now);
    seedEmployee(
        DemoIdentities.MANAGER_EMPLOYEE_ID,
        DemoIdentities.MANAGER_USER_ID,
        "EMP-011",
        "Michael Manager",
        "manager@demo.test",
        now);

    seedEmployee(
        DemoIdentities.EMPLOYEE_A_EMPLOYEE_ID,
        DemoIdentities.EMPLOYEE_A_USER_ID,
        "EMP-021",
        "Alice Engineer",
        "alice@demo.test",
        now);
    seedEmployee(
        DemoIdentities.EMPLOYEE_B_EMPLOYEE_ID,
        DemoIdentities.EMPLOYEE_B_USER_ID,
        "EMP-022",
        "Bob Engineer",
        "bob@demo.test",
        now);
    seedEmployee(
        DemoIdentities.EMPLOYEE_C_EMPLOYEE_ID,
        DemoIdentities.EMPLOYEE_C_USER_ID,
        "EMP-023",
        "Charlie Engineer",
        "charlie@demo.test",
        now);
    seedEmployee(
        DemoIdentities.EMPLOYEE_D_EMPLOYEE_ID,
        DemoIdentities.EMPLOYEE_D_USER_ID,
        "EMP-024",
        "David Engineer",
        "david.eng@demo.test",
        now);
    seedEmployee(
        DemoIdentities.EMPLOYEE_E_EMPLOYEE_ID,
        DemoIdentities.EMPLOYEE_E_USER_ID,
        "EMP-025",
        "Eve Engineer",
        "eve@demo.test",
        now);

    // 4. Position Assignments
    seedAssignment(
        DemoIdentities.DIRECTOR_EMPLOYEE_ID, DemoIdentities.POS_DIR_ENG_ID, effectiveDate, now);
    seedAssignment(
        DemoIdentities.MANAGER_EMPLOYEE_ID, DemoIdentities.POS_MGR_ENG_ID, effectiveDate, now);
    seedAssignment(
        DemoIdentities.EMPLOYEE_A_EMPLOYEE_ID, DemoIdentities.POS_STAFF_ENG_ID, effectiveDate, now);
    seedAssignment(
        DemoIdentities.EMPLOYEE_B_EMPLOYEE_ID, DemoIdentities.POS_STAFF_ENG_ID, effectiveDate, now);
    seedAssignment(
        DemoIdentities.EMPLOYEE_C_EMPLOYEE_ID, DemoIdentities.POS_STAFF_ENG_ID, effectiveDate, now);
    seedAssignment(
        DemoIdentities.EMPLOYEE_D_EMPLOYEE_ID, DemoIdentities.POS_STAFF_ENG_ID, effectiveDate, now);
    seedAssignment(
        DemoIdentities.EMPLOYEE_E_EMPLOYEE_ID, DemoIdentities.POS_STAFF_ENG_ID, effectiveDate, now);

    seedAssignment(
        DemoIdentities.HR_EMPLOYEE_ID, DemoIdentities.POS_HR_LEAD_ID, effectiveDate, now);
    seedAssignment(
        DemoIdentities.SECURITY_EMPLOYEE_ID, DemoIdentities.POS_SEC_LEAD_ID, effectiveDate, now);
    seedAssignment(
        DemoIdentities.FINANCE_EMPLOYEE_ID, DemoIdentities.POS_FIN_LEAD_ID, effectiveDate, now);
    seedAssignment(
        DemoIdentities.LEGAL_EMPLOYEE_ID, DemoIdentities.POS_LEG_LEAD_ID, effectiveDate, now);
  }

  private void seedOrgUnit(
      UUID id, String code, String name, UUID parentId, String unitType, Instant now) {
    if (!orgUnitRepository.existsById(id)) {
      orgUnitRepository.save(
          OrganizationUnit.create(id, code, name, name + " unit", parentId, unitType, now));
    }
  }

  private void seedPosition(
      UUID id,
      String code,
      String title,
      UUID unitId,
      UUID reportsToId,
      boolean head,
      Instant now) {
    if (!positionRepository.existsById(id)) {
      positionRepository.save(Position.create(id, code, title, unitId, reportsToId, head, now));
    }
  }

  private void seedEmployee(
      UUID id, UUID userId, String code, String name, String email, Instant now) {
    if (employeeRepository.findByUserId(userId).isEmpty()) {
      employeeRepository.save(Employee.create(id, userId, code, name, email, now));
    }
  }

  private void seedAssignment(UUID empId, UUID posId, LocalDate effectiveDate, Instant now) {
    if (assignmentRepository.findActiveAssignmentsForEmployee(empId, effectiveDate).isEmpty()) {
      assignmentRepository.save(
          PositionAssignment.create(
              UUID.randomUUID(), empId, posId, true, "PERMANENT", effectiveDate, null, now));
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 2. Demo Connectors Seeding
  // ────────────────────────────────────────────────────────────────────────────
  public void seedConnectors() {
    ActorContext techAdmin =
        new ActorContext(DemoIdentities.ADMIN_USER_ID, "admin", Set.of(RoleKey.ADMIN), Set.of());

    // DEMO_IAM Connector
    if (connectorRepository.findByKey(DemoIdentities.CONNECTOR_IAM).isEmpty()) {
      ObjectNode iamConfig = objectMapper.createObjectNode();
      iamConfig.put("baseUrl", "https://iam.internal.demo");
      connectorManagementService.registerConnector(
          DemoIdentities.CONNECTOR_IAM,
          "Demo IAM Provisioning Connector",
          "REST",
          "httpHandler",
          iamConfig,
          "vault://demo/iam",
          techAdmin);
    }

    ConnectorDefinition iamConnector =
        connectorRepository.findByKey(DemoIdentities.CONNECTOR_IAM).orElseThrow();
    if (actionRepository
        .findByConnectorIdAndActionKey(iamConnector.getId(), DemoIdentities.ACTION_PROVISION)
        .isEmpty()) {
      connectorManagementService.registerAction(
          DemoIdentities.CONNECTOR_IAM,
          DemoIdentities.ACTION_PROVISION,
          "Provision Access",
          techAdmin);
    }

    ConnectorAction iamAction =
        actionRepository
            .findByConnectorIdAndActionKey(iamConnector.getId(), DemoIdentities.ACTION_PROVISION)
            .orElseThrow();
    if (versionRepository.findByConnectorActionIdAndVersionNo(iamAction.getId(), 1).isEmpty()) {
      ObjectNode inSchema = objectMapper.createObjectNode();
      inSchema.put("type", "object");
      inSchema.putObject("properties").putObject("systemName").put("type", "string");
      inSchema.withObject("/properties").putObject("accessLevel").put("type", "string");

      ObjectNode outSchema = objectMapper.createObjectNode();
      outSchema.put("type", "object");
      outSchema.putObject("properties").putObject("status").put("type", "string");

      connectorManagementService.publishActionVersion(
          DemoIdentities.CONNECTOR_IAM,
          DemoIdentities.ACTION_PROVISION,
          1,
          inSchema,
          outSchema,
          objectMapper.createObjectNode(),
          objectMapper.createObjectNode(),
          objectMapper.createObjectNode(),
          objectMapper.createObjectNode(),
          objectMapper.createObjectNode(),
          techAdmin);
    }

    // DEMO_ERP Connector
    if (connectorRepository.findByKey(DemoIdentities.CONNECTOR_ERP).isEmpty()) {
      ObjectNode erpConfig = objectMapper.createObjectNode();
      erpConfig.put("baseUrl", "https://erp.internal.demo");
      connectorManagementService.registerConnector(
          DemoIdentities.CONNECTOR_ERP,
          "Demo ERP Gateway Connector",
          "REST",
          "httpHandler",
          erpConfig,
          "vault://demo/erp",
          techAdmin);
    }

    ConnectorDefinition erpConnector =
        connectorRepository.findByKey(DemoIdentities.CONNECTOR_ERP).orElseThrow();
    if (actionRepository
        .findByConnectorIdAndActionKey(erpConnector.getId(), DemoIdentities.ACTION_CREATE_PO)
        .isEmpty()) {
      connectorManagementService.registerAction(
          DemoIdentities.CONNECTOR_ERP,
          DemoIdentities.ACTION_CREATE_PO,
          "Create Purchase Order",
          techAdmin);
    }

    ConnectorAction erpAction =
        actionRepository
            .findByConnectorIdAndActionKey(erpConnector.getId(), DemoIdentities.ACTION_CREATE_PO)
            .orElseThrow();
    if (versionRepository.findByConnectorActionIdAndVersionNo(erpAction.getId(), 1).isEmpty()) {
      ObjectNode inSchema = objectMapper.createObjectNode();
      inSchema.put("type", "object");
      inSchema.putObject("properties").putObject("vendorName").put("type", "string");
      inSchema.withObject("/properties").putObject("amount").put("type", "integer");

      ObjectNode outSchema = objectMapper.createObjectNode();
      outSchema.put("type", "object");
      outSchema.putObject("properties").putObject("poNumber").put("type", "string");

      connectorManagementService.publishActionVersion(
          DemoIdentities.CONNECTOR_ERP,
          DemoIdentities.ACTION_CREATE_PO,
          1,
          inSchema,
          outSchema,
          objectMapper.createObjectNode(),
          objectMapper.createObjectNode(),
          objectMapper.createObjectNode(),
          objectMapper.createObjectNode(),
          objectMapper.createObjectNode(),
          techAdmin);
    }
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 3. Child Subworkflow: Vendor Verification
  // ────────────────────────────────────────────────────────────────────────────
  public WorkflowDefinition seedVendorVerificationWorkflow() {
    String key = DemoIdentities.KEY_VENDOR_VERIFICATION;
    Optional<WorkflowDefinition> existing = definitionRepository.findByKey(key);
    if (existing.isPresent() && existing.get().getCurrentPublishedVersionId() != null) {
      return existing.get();
    }

    Instant now = clock.now();
    WorkflowDefinition def =
        existing.orElseGet(
            () ->
                definitionRepository.save(
                    WorkflowDefinition.create(
                        UUID.randomUUID(),
                        key,
                        "Vendor Verification Subworkflow",
                        "Subworkflow verifying vendor credibility and compliance",
                        DemoIdentities.ADMIN_USER_ID,
                        DemoIdentities.ADMIN_USER_ID,
                        now)));

    WorkflowVersion draft =
        workflowVersionRepository.save(
            WorkflowVersion.createDraft(
                UUID.randomUUID(), def.getId(), 1, null, null, DemoIdentities.ADMIN_USER_ID, now));
    def.assignActiveDraft(draft.getId(), now);
    def = definitionRepository.save(def);

    // Nodes: START -> verify_vendor (REVIEW) -> END (VERIFIED), END (UNVERIFIED)
    NodeDefinition startNode = node(draft.getId(), "start", "START", "Start", 1, object());

    ObjectNode reviewCfg = object();
    reviewCfg.putArray("allowedActions").add("SUBMIT").add("RETURN");
    reviewCfg
        .putObject("participant")
        .put("type", "FIXED_USER")
        .put("userId", DemoIdentities.LEGAL_USER_ID.toString());
    NodeDefinition reviewNode =
        node(draft.getId(), "verify_vendor", "REVIEW", "Verify Vendor", 1, reviewCfg);

    NodeDefinition endVerified =
        node(
            draft.getId(),
            "end_verified",
            "END",
            "Vendor Verified",
            1,
            object().put("outcome", "VERIFIED"));
    NodeDefinition endUnverified =
        node(
            draft.getId(),
            "end_unverified",
            "END",
            "Vendor Unverified",
            1,
            object().put("outcome", "UNVERIFIED"));

    nodeRepository.saveAll(List.of(startNode, reviewNode, endVerified, endUnverified));

    // Edges
    edgeRepository.save(edge(draft.getId(), startNode, "STARTED", reviewNode, 0));
    edgeRepository.save(edge(draft.getId(), reviewNode, "SUBMITTED", endVerified, 0));
    edgeRepository.save(edge(draft.getId(), reviewNode, "RETURNED", endUnverified, 0));

    publishWorkflow(draft);
    return definitionRepository.findById(def.getId()).orElseThrow();
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 4. Workflow 1: Leave Request
  // ────────────────────────────────────────────────────────────────────────────
  public WorkflowDefinition seedLeaveRequestWorkflow() {
    String key = DemoIdentities.KEY_LEAVE_REQUEST;
    Optional<WorkflowDefinition> existing = definitionRepository.findByKey(key);
    if (existing.isPresent() && existing.get().getCurrentPublishedVersionId() != null) {
      return existing.get();
    }

    Instant now = clock.now();
    WorkflowDefinition def =
        existing.orElseGet(
            () ->
                definitionRepository.save(
                    WorkflowDefinition.create(
                        UUID.randomUUID(),
                        key,
                        "Leave Request Workflow",
                        "Standard employee leave request with manager approval and HR notification",
                        DemoIdentities.ADMIN_USER_ID,
                        DemoIdentities.ADMIN_USER_ID,
                        now)));

    WorkflowVersion draft =
        workflowVersionRepository.save(
            WorkflowVersion.createDraft(
                UUID.randomUUID(), def.getId(), 1, null, null, DemoIdentities.ADMIN_USER_ID, now));
    def.assignActiveDraft(draft.getId(), now);
    def = definitionRepository.save(def);

    // Form
    List<FormFieldDefinition> fields =
        List.of(
            field("leaveType", "Leave Type", 0, CanonicalValueType.STRING, true),
            field("startDate", "Start Date", 1, CanonicalValueType.STRING, true),
            field("endDate", "End Date", 2, CanonicalValueType.STRING, true),
            field("reason", "Reason", 3, CanonicalValueType.STRING, false));
    saveForm(draft.getId(), fields, "leave-form-v1");

    // Nodes
    NodeDefinition startNode = node(draft.getId(), "start", "START", "Start", 1, object());

    ObjectNode approvalCfg = object();
    approvalCfg.putArray("allowedActions").add("APPROVE").add("REJECT").add("REVISION_REQUESTED");
    approvalCfg.putObject("participant").put("type", "MANAGER_OF").put("depth", 1);
    NodeDefinition approvalNode =
        node(draft.getId(), "manager_approval", "APPROVAL", "Manager Approval", 1, approvalCfg);

    ObjectNode notifCfg = object();
    notifCfg.put("channel", "EMAIL");
    notifCfg.putObject("participant").put("type", "CREATOR");
    notifCfg
        .putObject("template")
        .put("title", "Leave Request Approved")
        .put("body", "Your leave request has been approved.");
    NodeDefinition notifNode =
        node(draft.getId(), "hr_notification", "NOTIFICATION", "HR Notification", 1, notifCfg);

    NodeDefinition endApproved =
        node(
            draft.getId(),
            "end_approved",
            "END",
            "Approved End",
            1,
            object().put("outcome", "APPROVED"));
    NodeDefinition endRejected =
        node(
            draft.getId(),
            "end_rejected",
            "END",
            "Rejected End",
            1,
            object().put("outcome", "REJECTED"));

    nodeRepository.saveAll(List.of(startNode, approvalNode, notifNode, endApproved, endRejected));

    // Edges
    edgeRepository.save(edge(draft.getId(), startNode, "STARTED", approvalNode, 0));
    edgeRepository.save(edge(draft.getId(), approvalNode, "APPROVED", notifNode, 0));
    edgeRepository.save(edge(draft.getId(), notifNode, "QUEUED", endApproved, 0));
    edgeRepository.save(edge(draft.getId(), approvalNode, "REJECTED", endRejected, 0));
    edgeRepository.save(edge(draft.getId(), approvalNode, "REVISION_REQUESTED", endRejected, 1));

    publishWorkflow(draft);
    seedRequestType(DemoIdentities.REQ_LEAVE_REQUEST, "Leave Request", "HR", def.getId(), now);

    return definitionRepository.findById(def.getId()).orElseThrow();
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 5. Workflow 2: Access Request
  // ────────────────────────────────────────────────────────────────────────────
  public WorkflowDefinition seedAccessRequestWorkflow() {
    String key = DemoIdentities.KEY_ACCESS_REQUEST;
    Optional<WorkflowDefinition> existing = definitionRepository.findByKey(key);
    if (existing.isPresent() && existing.get().getCurrentPublishedVersionId() != null) {
      return existing.get();
    }

    Instant now = clock.now();
    WorkflowDefinition def =
        existing.orElseGet(
            () ->
                definitionRepository.save(
                    WorkflowDefinition.create(
                        UUID.randomUUID(),
                        key,
                        "Access Request Workflow",
                        "System access request with manager approval, security review, and IAM provisioning",
                        DemoIdentities.ADMIN_USER_ID,
                        DemoIdentities.ADMIN_USER_ID,
                        now)));

    WorkflowVersion draft =
        workflowVersionRepository.save(
            WorkflowVersion.createDraft(
                UUID.randomUUID(), def.getId(), 1, null, null, DemoIdentities.ADMIN_USER_ID, now));
    def.assignActiveDraft(draft.getId(), now);
    def = definitionRepository.save(def);

    // Form
    List<FormFieldDefinition> fields =
        List.of(
            field("systemName", "System Name", 0, CanonicalValueType.STRING, true),
            field("accessLevel", "Access Level", 1, CanonicalValueType.STRING, true),
            field("justification", "Justification", 2, CanonicalValueType.STRING, true));
    saveForm(draft.getId(), fields, "access-form-v1");

    // Nodes
    NodeDefinition startNode = node(draft.getId(), "start", "START", "Start", 1, object());

    ObjectNode approvalCfg = object();
    approvalCfg.putArray("allowedActions").add("APPROVE").add("REJECT").add("REVISION_REQUESTED");
    approvalCfg.putObject("participant").put("type", "MANAGER_OF").put("depth", 1);
    NodeDefinition approvalNode =
        node(draft.getId(), "manager_approval", "APPROVAL", "Manager Approval", 1, approvalCfg);

    ObjectNode reviewCfg = object();
    reviewCfg.putArray("allowedActions").add("SUBMIT").add("RETURN");
    reviewCfg
        .putObject("participant")
        .put("type", "FIXED_USER")
        .put("userId", DemoIdentities.SECURITY_USER_ID.toString());
    NodeDefinition reviewNode =
        node(draft.getId(), "security_review", "REVIEW", "Security Review", 1, reviewCfg);

    ObjectNode actionCfg = object();
    actionCfg.put("connectorKey", DemoIdentities.CONNECTOR_IAM);
    actionCfg.put("actionKey", DemoIdentities.ACTION_PROVISION);
    actionCfg.put("actionVersion", 1);
    actionCfg.put("credentialRef", "vault://demo/iam");
    NodeDefinition actionNode =
        node(draft.getId(), "provision_access", "SYSTEM_ACTION", "Provision Access", 1, actionCfg);

    NodeDefinition endCompleted =
        node(
            draft.getId(),
            "end_completed",
            "END",
            "Completed End",
            1,
            object().put("outcome", "COMPLETED"));
    NodeDefinition endRejected =
        node(
            draft.getId(),
            "end_rejected",
            "END",
            "Rejected End",
            1,
            object().put("outcome", "REJECTED"));

    nodeRepository.saveAll(
        List.of(startNode, approvalNode, reviewNode, actionNode, endCompleted, endRejected));

    // Edges
    edgeRepository.save(edge(draft.getId(), startNode, "STARTED", approvalNode, 0));
    edgeRepository.save(edge(draft.getId(), approvalNode, "APPROVED", reviewNode, 0));
    edgeRepository.save(edge(draft.getId(), approvalNode, "REJECTED", endRejected, 0));
    edgeRepository.save(edge(draft.getId(), approvalNode, "REVISION_REQUESTED", endRejected, 1));
    edgeRepository.save(edge(draft.getId(), reviewNode, "SUBMITTED", actionNode, 0));
    edgeRepository.save(edge(draft.getId(), reviewNode, "RETURNED", endRejected, 0));
    edgeRepository.save(edge(draft.getId(), actionNode, "SUCCESS", endCompleted, 0));
    edgeRepository.save(edge(draft.getId(), actionNode, "ERROR", endRejected, 0));

    publishWorkflow(draft);
    seedRequestType(DemoIdentities.REQ_ACCESS_REQUEST, "Access Request", "IT", def.getId(), now);

    return definitionRepository.findById(def.getId()).orElseThrow();
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 6. Workflow 3: Purchase Request
  // ────────────────────────────────────────────────────────────────────────────
  public WorkflowDefinition seedPurchaseRequestWorkflow() {
    String key = DemoIdentities.KEY_PURCHASE_REQUEST;
    Optional<WorkflowDefinition> existing = definitionRepository.findByKey(key);
    if (existing.isPresent() && existing.get().getCurrentPublishedVersionId() != null) {
      return existing.get();
    }

    Instant now = clock.now();
    WorkflowDefinition def =
        existing.orElseGet(
            () ->
                definitionRepository.save(
                    WorkflowDefinition.create(
                        UUID.randomUUID(),
                        key,
                        "Purchase Request Workflow",
                        "Purchase approval with vendor verification subworkflow, parallel finance & legal reviews, and ERP PO creation",
                        DemoIdentities.ADMIN_USER_ID,
                        DemoIdentities.ADMIN_USER_ID,
                        now)));

    WorkflowVersion draft =
        workflowVersionRepository.save(
            WorkflowVersion.createDraft(
                UUID.randomUUID(), def.getId(), 1, null, null, DemoIdentities.ADMIN_USER_ID, now));
    def.assignActiveDraft(draft.getId(), now);
    def = definitionRepository.save(def);

    // Form
    List<FormFieldDefinition> fields =
        List.of(
            field("vendorName", "Vendor Name", 0, CanonicalValueType.STRING, true),
            field("amount", "Amount", 1, CanonicalValueType.INTEGER, true),
            field("category", "Category", 2, CanonicalValueType.STRING, true),
            field("description", "Description", 3, CanonicalValueType.STRING, false));
    saveForm(draft.getId(), fields, "purchase-form-v1");

    // Nodes
    NodeDefinition startNode = node(draft.getId(), "start", "START", "Start", 1, object());

    ObjectNode approvalCfg = object();
    approvalCfg.putArray("allowedActions").add("APPROVE").add("REJECT").add("REVISION_REQUESTED");
    approvalCfg.putObject("participant").put("type", "MANAGER_OF").put("depth", 1);
    NodeDefinition managerNode =
        node(draft.getId(), "manager_approval", "APPROVAL", "Manager Approval", 1, approvalCfg);

    ObjectNode subwfCfg = object();
    subwfCfg.put("childWorkflowDefinitionKey", DemoIdentities.KEY_VENDOR_VERIFICATION);
    subwfCfg.put("executionMode", "WAIT_FOR_COMPLETION");
    subwfCfg.put("cancellationPolicy", "PROPAGATE");
    NodeDefinition subwfNode =
        node(
            draft.getId(),
            "vendor_subworkflow",
            "SUB_WORKFLOW",
            "Vendor Verification",
            1,
            subwfCfg);

    ObjectNode splitCfg = object();
    splitCfg.put("routingMode", "ALL_OUTGOING");
    NodeDefinition splitNode =
        node(draft.getId(), "parallel_split", "PARALLEL_SPLIT", "Parallel Split", 1, splitCfg);

    ObjectNode finCfg = object();
    finCfg.putArray("allowedActions").add("APPROVE").add("REJECT").add("REVISION_REQUESTED");
    finCfg
        .putObject("participant")
        .put("type", "FIXED_USER")
        .put("userId", DemoIdentities.FINANCE_USER_ID.toString());
    NodeDefinition financeNode =
        node(draft.getId(), "finance_approval", "APPROVAL", "Finance Approval", 1, finCfg);

    ObjectNode legalCfg = object();
    legalCfg.putArray("allowedActions").add("SUBMIT").add("RETURN");
    legalCfg
        .putObject("participant")
        .put("type", "FIXED_USER")
        .put("userId", DemoIdentities.LEGAL_USER_ID.toString());
    NodeDefinition legalNode =
        node(draft.getId(), "legal_review", "REVIEW", "Legal Review", 1, legalCfg);

    ObjectNode joinCfg = object();
    joinCfg.put("policy", "ALL");
    NodeDefinition joinNode =
        node(draft.getId(), "join_all", "JOIN", "Join All Reviews", 1, joinCfg);

    ObjectNode erpCfg = object();
    erpCfg.put("connectorKey", DemoIdentities.CONNECTOR_ERP);
    erpCfg.put("actionKey", DemoIdentities.ACTION_CREATE_PO);
    erpCfg.put("actionVersion", 1);
    erpCfg.put("credentialRef", "vault://demo/erp");
    NodeDefinition erpNode =
        node(draft.getId(), "erp_create_po", "SYSTEM_ACTION", "Create ERP PO", 1, erpCfg);

    ObjectNode notifCfg = object();
    notifCfg.put("channel", "EMAIL");
    notifCfg.putObject("participant").put("type", "CREATOR");
    notifCfg
        .putObject("template")
        .put("title", "Purchase Order Created")
        .put("body", "Your purchase order has been generated in ERP.");
    NodeDefinition notifNode =
        node(draft.getId(), "po_notification", "NOTIFICATION", "PO Notification", 1, notifCfg);

    NodeDefinition endCompleted =
        node(
            draft.getId(),
            "end_completed",
            "END",
            "Completed End",
            1,
            object().put("outcome", "COMPLETED"));
    NodeDefinition endRejected =
        node(
            draft.getId(),
            "end_rejected",
            "END",
            "Rejected End",
            1,
            object().put("outcome", "REJECTED"));

    nodeRepository.saveAll(
        List.of(
            startNode,
            managerNode,
            subwfNode,
            splitNode,
            financeNode,
            legalNode,
            joinNode,
            erpNode,
            notifNode,
            endCompleted,
            endRejected));

    // Edges
    edgeRepository.save(edge(draft.getId(), startNode, "STARTED", managerNode, 0));
    edgeRepository.save(edge(draft.getId(), managerNode, "APPROVED", subwfNode, 0));
    edgeRepository.save(edge(draft.getId(), managerNode, "REJECTED", endRejected, 0));
    edgeRepository.save(edge(draft.getId(), managerNode, "REVISION_REQUESTED", endRejected, 1));
    edgeRepository.save(edge(draft.getId(), subwfNode, "COMPLETED", splitNode, 0));
    edgeRepository.save(edge(draft.getId(), subwfNode, "FAILED", endRejected, 0));
    edgeRepository.save(edge(draft.getId(), subwfNode, "CANCELLED", endRejected, 1));
    edgeRepository.save(edge(draft.getId(), subwfNode, "REJECTED", endRejected, 2));
    edgeRepository.save(edge(draft.getId(), splitNode, "SPLIT", financeNode, 0));
    edgeRepository.save(edge(draft.getId(), splitNode, "SPLIT", legalNode, 1));
    edgeRepository.save(edge(draft.getId(), financeNode, "APPROVED", joinNode, 0));
    edgeRepository.save(edge(draft.getId(), financeNode, "REJECTED", endRejected, 0));
    edgeRepository.save(edge(draft.getId(), financeNode, "REVISION_REQUESTED", endRejected, 1));
    edgeRepository.save(edge(draft.getId(), legalNode, "SUBMITTED", joinNode, 0));
    edgeRepository.save(edge(draft.getId(), legalNode, "RETURNED", endRejected, 0));
    edgeRepository.save(edge(draft.getId(), joinNode, "DEFAULT", erpNode, 0));
    edgeRepository.save(edge(draft.getId(), erpNode, "SUCCESS", notifNode, 0));
    edgeRepository.save(edge(draft.getId(), erpNode, "ERROR", endRejected, 0));
    edgeRepository.save(edge(draft.getId(), notifNode, "QUEUED", endCompleted, 0));

    publishWorkflow(draft);
    seedRequestType(
        DemoIdentities.REQ_PURCHASE_REQUEST, "Purchase Request", "FINANCE", def.getId(), now);

    return definitionRepository.findById(def.getId()).orElseThrow();
  }

  // ────────────────────────────────────────────────────────────────────────────
  // 7. Workflow 4: Employee Evaluation
  // ────────────────────────────────────────────────────────────────────────────
  public WorkflowDefinition seedEmployeeEvaluationWorkflow() {
    String key = DemoIdentities.KEY_EMPLOYEE_EVALUATION;
    Optional<WorkflowDefinition> existing = definitionRepository.findByKey(key);
    if (existing.isPresent() && existing.get().getCurrentPublishedVersionId() != null) {
      return existing.get();
    }

    Instant now = clock.now();
    WorkflowDefinition def =
        existing.orElseGet(
            () ->
                definitionRepository.save(
                    WorkflowDefinition.create(
                        UUID.randomUUID(),
                        key,
                        "Employee Evaluation Workflow",
                        "Multi-instance performance evaluation with self evaluation, manager review, rework loop, and HR signoff",
                        DemoIdentities.ADMIN_USER_ID,
                        DemoIdentities.ADMIN_USER_ID,
                        now)));

    WorkflowVersion draft =
        workflowVersionRepository.save(
            WorkflowVersion.createDraft(
                UUID.randomUUID(), def.getId(), 1, null, null, DemoIdentities.ADMIN_USER_ID, now));
    def.assignActiveDraft(draft.getId(), now);
    def = definitionRepository.save(def);

    // Form
    List<FormFieldDefinition> fields =
        List.of(
            field("period", "Evaluation Period", 0, CanonicalValueType.STRING, true),
            new FormFieldDefinition(
                UUID.randomUUID(),
                "evaluationTargets",
                "Evaluation Targets",
                null,
                null,
                1,
                TypeDescriptor.arrayOf(TypeDescriptor.required(CanonicalValueType.STRING)),
                null,
                false,
                FieldRequirement.always(),
                FieldVisibility.always(),
                FieldEditability.editable(),
                FieldValidationRules.none(),
                null,
                new FieldSemanticMetadata(false, false, false, false, false)));
    saveForm(draft.getId(), fields, "evaluation-form-v1");

    // Nodes
    NodeDefinition startNode = node(draft.getId(), "start", "START", "Start", 1, object());

    ObjectNode selfCfg = object();
    selfCfg.putArray("allowedActions").add("SUBMIT").add("RETURN");
    selfCfg.putObject("participant").put("type", "ITEM_USER");
    selfCfg
        .putObject("multiInstance")
        .put("collectionPath", "ticket.data.evaluationTargets")
        .put("executionMode", "PARALLEL")
        .put("completionPolicy", "ALL")
        .put("remainingItemPolicy", "CANCEL_REMAINING");
    NodeDefinition selfNode =
        node(
            draft.getId(),
            "self_evaluation",
            "REVIEW",
            "Self Evaluation (Multi-Instance)",
            1,
            selfCfg);

    ObjectNode mgrCfg = object();
    mgrCfg.putArray("allowedActions").add("SUBMIT").add("RETURN");
    mgrCfg.putObject("participant").put("type", "ITEM_MANAGER").put("depth", 1);
    mgrCfg
        .putObject("multiInstance")
        .put("collectionPath", "ticket.data.evaluationTargets")
        .put("executionMode", "PARALLEL")
        .put("completionPolicy", "ALL")
        .put("remainingItemPolicy", "CANCEL_REMAINING");
    NodeDefinition mgrNode =
        node(
            draft.getId(),
            "manager_review",
            "REVIEW",
            "Manager Review (Multi-Instance)",
            1,
            mgrCfg);

    ObjectNode higherCfg = object();
    higherCfg.putArray("allowedActions").add("APPROVE").add("REJECT").add("REVISION_REQUESTED");
    higherCfg.putObject("participant").put("type", "MANAGER_OF").put("depth", 1);
    NodeDefinition higherNode =
        node(
            draft.getId(),
            "higher_manager_approval",
            "APPROVAL",
            "Higher Manager Approval",
            1,
            higherCfg);

    ObjectNode hrCfg = object();
    hrCfg.putArray("allowedActions").add("SUBMIT").add("RETURN");
    hrCfg
        .putObject("participant")
        .put("type", "FIXED_USER")
        .put("userId", DemoIdentities.HR_USER_ID.toString());
    NodeDefinition hrNode = node(draft.getId(), "hr_review", "REVIEW", "HR Review", 1, hrCfg);

    NodeDefinition endCompleted =
        node(
            draft.getId(),
            "end_completed",
            "END",
            "Completed End",
            1,
            object().put("outcome", "COMPLETED"));
    NodeDefinition endRejected =
        node(
            draft.getId(),
            "end_rejected",
            "END",
            "Rejected End",
            1,
            object().put("outcome", "REJECTED"));

    nodeRepository.saveAll(
        List.of(startNode, selfNode, mgrNode, higherNode, hrNode, endCompleted, endRejected));

    // Edges
    edgeRepository.save(edge(draft.getId(), startNode, "STARTED", selfNode, 0));
    edgeRepository.save(edge(draft.getId(), selfNode, "SUBMITTED", mgrNode, 0));
    edgeRepository.save(edge(draft.getId(), selfNode, "RETURNED", endRejected, 0));
    edgeRepository.save(edge(draft.getId(), mgrNode, "SUBMITTED", higherNode, 0));
    edgeRepository.save(edge(draft.getId(), mgrNode, "RETURNED", endRejected, 0));
    edgeRepository.save(edge(draft.getId(), higherNode, "APPROVED", hrNode, 0));

    // Bounded rework loop: higherNode (REJECTED) -> mgrNode
    ObjectNode reworkCfg = object();
    reworkCfg
        .putObject("reworkPolicy")
        .put("maxIterations", 3)
        .put("onExhausted", "FAIL_EVENT")
        .put("scope", "WHOLE_NODE");
    EdgeDefinition reworkEdge =
        EdgeDefinition.create(
            UUID.randomUUID(),
            draft.getId(),
            higherNode.getId(),
            "REJECTED",
            mgrNode.getId(),
            null,
            0,
            false,
            TransitionType.REWORK,
            null,
            reworkCfg);
    edgeRepository.save(reworkEdge);

    edgeRepository.save(edge(draft.getId(), higherNode, "REVISION_REQUESTED", endRejected, 1));
    edgeRepository.save(edge(draft.getId(), hrNode, "SUBMITTED", endCompleted, 0));
    edgeRepository.save(edge(draft.getId(), hrNode, "RETURNED", endRejected, 0));

    publishWorkflow(draft);
    seedRequestType(
        DemoIdentities.REQ_EMPLOYEE_EVALUATION, "Employee Evaluation", "HR", def.getId(), now);

    return definitionRepository.findById(def.getId()).orElseThrow();
  }

  // ────────────────────────────────────────────────────────────────────────────
  // Shared Publishing & Fixture Utilities
  // ────────────────────────────────────────────────────────────────────────────
  private void publishWorkflow(WorkflowVersion draft) {
    ValidationCompilation compilation = validationService.compileCurrent(draft.getId());
    if (!compilation.valid() || !compilation.publishable()) {
      throw new IllegalStateException(
          "Workflow " + draft.getDefinitionId() + " failed validation: " + compilation.issues());
    }

    publishService.publish(
        draft.getId(),
        new ExpectedVersion(draft.getLockVersion()),
        draft.getRevision(),
        new CommandId(UUID.randomUUID()));
  }

  private void seedRequestType(
      String key, String name, String category, UUID workflowDefId, Instant now) {
    if (requestTypeRepository.findByKey(key).isEmpty()) {
      requestTypeRepository.save(
          RequestType.create(
              UUID.randomUUID(),
              key,
              name,
              name + " request catalog type",
              category,
              workflowDefId,
              true,
              objectMapper.createObjectNode(),
              now));
    }
  }

  private void saveForm(UUID versionId, List<FormFieldDefinition> fields, String checksum) {
    FormSchema schema = new FormSchema("ticket", WorkflowFormType.TICKET_FORM, fields);
    formRepository.save(
        WorkflowForm.create(
            UUID.randomUUID(),
            versionId,
            "ticket",
            WorkflowFormType.TICKET_FORM,
            objectMapper.valueToTree(schema),
            checksum));
  }

  private FormFieldDefinition field(
      String key, String label, int order, CanonicalValueType type, boolean required) {
    return new FormFieldDefinition(
        UUID.randomUUID(),
        key,
        label,
        null,
        null,
        order,
        required ? TypeDescriptor.required(type) : TypeDescriptor.nullable(type),
        null,
        false,
        required ? FieldRequirement.always() : FieldRequirement.never(),
        FieldVisibility.always(),
        FieldEditability.editable(),
        FieldValidationRules.none(),
        null,
        new FieldSemanticMetadata(false, false, false, false, false));
  }

  private NodeDefinition node(
      UUID versionId,
      String key,
      String type,
      String name,
      int configSchemaVersion,
      ObjectNode config) {
    return NodeDefinition.create(
        UUID.randomUUID(),
        versionId,
        key,
        type,
        name,
        name + " description",
        configSchemaVersion,
        config != null ? config : object(),
        null,
        null,
        object().put("x", 100).put("y", 100));
  }

  private EdgeDefinition edge(
      UUID versionId,
      NodeDefinition source,
      String sourcePort,
      NodeDefinition target,
      int priority) {
    return EdgeDefinition.create(
        UUID.randomUUID(),
        versionId,
        source.getId(),
        sourcePort,
        target.getId(),
        null,
        priority,
        false,
        TransitionType.NORMAL,
        null,
        object());
  }

  private ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }

  private void runAsAdmin(Runnable action) {
    SecurityContext prevContext = SecurityContextHolder.getContext();
    try {
      var principal =
          new AuthenticatedActorPrincipal(DemoIdentities.ADMIN_USER_ID, "admin@demo.test");
      var auth =
          UsernamePasswordAuthenticationToken.authenticated(
              principal,
              "N/A",
              List.of(
                  new SimpleGrantedAuthority("ROLE_ADMIN"),
                  new SimpleGrantedAuthority("ROLE_WORKFLOW_OWNER"),
                  new SimpleGrantedAuthority("ROLE_USER")));
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(auth);
      SecurityContextHolder.setContext(context);
      action.run();
    } finally {
      SecurityContextHolder.setContext(prevContext);
    }
  }
}
