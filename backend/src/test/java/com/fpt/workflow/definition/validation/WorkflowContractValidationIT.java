package com.fpt.workflow.definition.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.*;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.form.domain.FormDefinition;
import com.fpt.workflow.form.domain.FormVersion;
import com.fpt.workflow.form.repository.FormDefinitionRepository;
import com.fpt.workflow.form.repository.FormVersionRepository;
import com.fpt.workflow.resolver.expression.ReferenceExpression;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** v2.4.1 §7.4 / §34.10: compiler validation of inputs.*, states, and pinned task FormVersions. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class WorkflowContractValidationIT {

  static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_contract_validation_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private WorkflowValidationCompiler compiler;
  @Autowired private WorkflowDefinitionRepository definitionRepository;
  @Autowired private WorkflowVersionRepository versionRepository;
  @Autowired private FormDefinitionRepository formDefinitionRepository;
  @Autowired private FormVersionRepository formVersionRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void validInputsReference_sameType_isAccepted() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", emptyConfig());
    NodeDefinition review = reviewReadingAmount(version, TypeDescriptor.required(CanonicalValueType.NUMBER));
    NodeDefinition end = node(version, "end", "END", emptyConfig());
    ValidationDefinition definition =
        reviewGraph(version, start, review, end, List.of(input(version, "amount", TypeDescriptor.required(CanonicalValueType.NUMBER), true)), List.of());
    assertThat(errorCodes(compiler.compile(definition)))
        .doesNotContain("UNKNOWN_WORKFLOW_INPUT", "WORKFLOW_INPUT_TYPE_MISMATCH", "UNKNOWN_BUSINESS_STATE");
  }

  @Test
  void unknownInputsReference_failsPublish() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", emptyConfig());
    NodeDefinition review = reviewReadingAmount(version, TypeDescriptor.required(CanonicalValueType.NUMBER));
    NodeDefinition end = node(version, "end", "END", emptyConfig());
    ValidationDefinition definition = reviewGraph(version, start, review, end, List.of(), List.of());
    assertThat(errorCodes(compiler.compile(definition))).contains("UNKNOWN_WORKFLOW_INPUT");
  }

  @Test
  void mistypedInputsUsage_failsPublish() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", emptyConfig());
    NodeDefinition review = reviewReadingAmount(version, TypeDescriptor.required(CanonicalValueType.STRING));
    NodeDefinition end = node(version, "end", "END", emptyConfig());
    ValidationDefinition definition =
        reviewGraph(
            version, start, review, end, List.of(input(version, "amount", TypeDescriptor.required(CanonicalValueType.NUMBER), true)), List.of());
    assertThat(errorCodes(compiler.compile(definition))).contains("WORKFLOW_INPUT_TYPE_MISMATCH");
  }

  @Test
  void undeclaredBusinessState_failsPublish() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", emptyConfig());
    ObjectNode config = reviewConfig(TypeDescriptor.required(CanonicalValueType.NUMBER));
    config.put("businessStateKey", "NOT_DECLARED");
    NodeDefinition review = node(version, "review", "REVIEW", config);
    NodeDefinition end = node(version, "end", "END", emptyConfig());
    ValidationDefinition definition =
        reviewGraph(
            version, start, review, end, List.of(input(version, "amount", TypeDescriptor.required(CanonicalValueType.NUMBER), true)), List.of());
    assertThat(errorCodes(compiler.compile(definition))).contains("UNKNOWN_BUSINESS_STATE");
  }

  @Test
  void validPublishedTaskFormVersion_isAccepted() {
    WorkflowVersion version = version();
    FormVersion published = publishedFormVersion();
    NodeDefinition start = node(version, "start", "START", emptyConfig());
    NodeDefinition review = reviewWithForm(version, published.getId());
    NodeDefinition end = node(version, "end", "END", emptyConfig());
    assertThat(errorCodes(compiler.compile(graph(version, start, review, end))))
        .doesNotContain("TASK_FORM_VERSION_MISSING", "TASK_FORM_VERSION_NOT_PUBLISHED", "TASK_FORM_VERSION_NOT_PINNED");
  }

  @Test
  void missingTaskFormVersion_failsPublish() {
    WorkflowVersion version = version();
    NodeDefinition start = node(version, "start", "START", emptyConfig());
    NodeDefinition review = reviewWithForm(version, UUID.randomUUID());
    NodeDefinition end = node(version, "end", "END", emptyConfig());
    assertThat(errorCodes(compiler.compile(graph(version, start, review, end)))).contains("TASK_FORM_VERSION_MISSING");
  }

  @Test
  void unpublishedTaskFormVersion_failsPublish() {
    WorkflowVersion version = version();
    FormVersion draft = draftFormVersion();
    NodeDefinition start = node(version, "start", "START", emptyConfig());
    NodeDefinition review = reviewWithForm(version, draft.getId());
    NodeDefinition end = node(version, "end", "END", emptyConfig());
    assertThat(errorCodes(compiler.compile(graph(version, start, review, end)))).contains("TASK_FORM_VERSION_NOT_PUBLISHED");
  }

  @Test
  void keyOnlyTaskFormReference_isRejectedAsUnpinned() {
    WorkflowVersion version = version();
    ObjectNode config = reviewConfig(TypeDescriptor.required(CanonicalValueType.NUMBER));
    config.remove("taskFormVersionId");
    config.put("formKey", "reviewForm");
    NodeDefinition start = node(version, "start", "START", emptyConfig());
    NodeDefinition review = node(version, "review", "REVIEW", config);
    NodeDefinition end = node(version, "end", "END", emptyConfig());
    assertThat(errorCodes(compiler.compile(graph(version, start, review, end)))).contains("TASK_FORM_VERSION_NOT_PINNED");
  }

  @Test
  void oldPublishedWorkflow_staysPinnedAfterNewerFormVersionPublish() {
    WorkflowVersion version = version();
    FormVersion oldForm = publishedFormVersion();
    NodeDefinition start = node(version, "start", "START", emptyConfig());
    NodeDefinition review = reviewWithForm(version, oldForm.getId());
    NodeDefinition end = node(version, "end", "END", emptyConfig());
    ValidationDefinition definition = graph(version, start, review, end);
    FormVersion newer = publishedFormVersion();
    assertThat(review.getConfigJson().path("taskFormVersionId").asText())
        .isEqualTo(oldForm.getId().toString())
        .isNotEqualTo(newer.getId().toString());
    assertThat(errorCodes(compiler.compile(definition)))
        .doesNotContain("TASK_FORM_VERSION_MISSING", "TASK_FORM_VERSION_NOT_PUBLISHED");
  }

  // --- graph/type helpers ----------------------------------------------------

  private List<String> errorCodes(ValidationCompilation result) {
    return result.issues().stream()
        .filter(i -> i.severity() == ValidationSeverity.ERROR)
        .map(CompilerIssue::code)
        .toList();
  }

  private WorkflowVersion version() {
    WorkflowDefinition definition =
        definitionRepository.saveAndFlush(
            WorkflowDefinition.create(
                UUID.randomUUID(),
                "wf-" + UUID.randomUUID(),
                "Contract Validation Workflow",
                null,
                ACTOR,
                ACTOR,
                Instant.EPOCH));
    return versionRepository.saveAndFlush(
        WorkflowVersion.createDraft(
            UUID.randomUUID(), definition.getId(), 1, null, null, ACTOR, Instant.EPOCH));
  }

  private ObjectNode emptyConfig() {
    return JsonNodeFactory.instance.objectNode();
  }

  private ValidationDefinition graph(
      WorkflowVersion version, NodeDefinition start, NodeDefinition review, NodeDefinition end) {
    return new ValidationDefinition(
        version,
        List.of(start, review, end),
        List.of(
            edge(version, start, "STARTED", review),
            edge(version, review, "SUBMITTED", end),
            edge(version, review, "RETURNED", end)),
        List.of(),
        List.of());
  }

  private ValidationDefinition reviewGraph(
      WorkflowVersion version,
      NodeDefinition start,
      NodeDefinition review,
      NodeDefinition end,
      List<WorkflowInputDefinition> inputs,
      List<WorkflowStateDefinition> states) {
    return new ValidationDefinition(
        version,
        List.of(start, review, end),
        List.of(
            edge(version, start, "STARTED", review),
            edge(version, review, "SUBMITTED", end),
            edge(version, review, "RETURNED", end)),
        List.of(),
        List.of(),
        inputs,
        states);
  }

  private ObjectNode reviewConfig(TypeDescriptor consumingType) {
    ObjectNode config = emptyConfig();
    ArrayBindings.bindAmount(config, consumingType, objectMapper);
    config.put("businessStateKey", "SUBMITTED");
    config.putObject("participant").put("type", "FIXED_USER").put("userId", UUID.randomUUID().toString());
    config.putArray("allowedActions").add("SUBMIT").add("RETURN");
    return config;
  }

  private NodeDefinition reviewReadingAmount(WorkflowVersion version, TypeDescriptor consumingType) {
    return node(version, "review", "REVIEW", reviewConfig(consumingType));
  }

  private NodeDefinition reviewWithForm(WorkflowVersion version, UUID formVersionId) {
    ObjectNode config = reviewConfig(TypeDescriptor.required(CanonicalValueType.NUMBER));
    config.put("taskFormVersionId", formVersionId.toString());
    return node(version, "review", "REVIEW", config);
  }

  private WorkflowInputDefinition input(WorkflowVersion version, String key, TypeDescriptor type, boolean required) {
    return WorkflowInputDefinition.create(
        UUID.randomUUID(), version.getId(), key, null, type, required, null, null, false, null, 0);
  }

  private NodeDefinition node(WorkflowVersion version, String key, String type, JsonNode config) {
    return NodeDefinition.create(
        UUID.randomUUID(), version.getId(), key, type, key, null, 1, config, null, null, emptyConfig());
  }

  private EdgeDefinition edge(WorkflowVersion version, NodeDefinition from, String port, NodeDefinition to) {
    return EdgeDefinition.create(
        UUID.randomUUID(),
        version.getId(),
        from.getId(),
        port,
        to.getId(),
        null,
        0,
        false,
        TransitionType.NORMAL,
        null,
        emptyConfig());
  }

  private FormVersion publishedFormVersion() {
    FormVersion draft = draftFormVersion();
    draft.publish("checksum-" + UUID.randomUUID(), draft.getSchemaJson(), ACTOR, Instant.now());
    return formVersionRepository.saveAndFlush(draft);
  }

  private FormVersion draftFormVersion() {
    FormDefinition form =
        formDefinitionRepository.saveAndFlush(
            FormDefinition.create(
                UUID.randomUUID(),
                "form-" + UUID.randomUUID(),
                "Contract Form",
                null,
                ACTOR,
                Instant.now()));
    ObjectNode schema = emptyConfig();
    schema.put("formKey", form.getKey());
    schema.put("formType", "TICKET_FORM");
    schema.set("fields", objectMapper.createArrayNode());
    return formVersionRepository.saveAndFlush(
        FormVersion.draft(UUID.randomUUID(), form.getId(), 1, ACTOR, Instant.now(), schema));
  }

  private static final class ArrayBindings {
    static void bindAmount(ObjectNode config, TypeDescriptor consumingType, ObjectMapper mapper) {
      var bindings = config.putArray("inputBindings");
      var binding = bindings.addObject();
      binding.put("target", "amount");
      binding.set("expression", mapper.valueToTree(new ReferenceExpression("inputs.amount")));
      binding.set("expectedType", mapper.valueToTree(consumingType));
      binding.put("required", true);
      binding.put("onMissing", "ERROR");
    }
  }
}
