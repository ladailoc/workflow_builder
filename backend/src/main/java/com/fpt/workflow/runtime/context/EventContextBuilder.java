package com.fpt.workflow.runtime.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVariableRepository;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.resolver.expression.ExpressionSchema;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.masking.SensitiveValueMasker;
import com.fpt.workflow.shared.domain.value.CanonicalSchema;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds a fresh expression context from immutable snapshots and authoritative runtime rows. */
@Service
public class EventContextBuilder {

  private static final TypeDescriptor OBJECT = TypeDescriptor.required(CanonicalValueType.OBJECT);
  private static final TypeDescriptor STRING = TypeDescriptor.required(CanonicalValueType.STRING);
  private static final TypeDescriptor INTEGER = TypeDescriptor.required(CanonicalValueType.INTEGER);

  private final EventRepository eventRepository;
  private final NodeExecutionRepository executionRepository;
  private final NodeDefinitionRepository nodeRepository;
  private final WorkflowVariableRepository variableRepository;
  private final WorkflowFormRepository formRepository;
  private final TicketContextSource ticketSource;
  private final ActorContextProvider actorProvider;
  private final EventContextNamespaceProvider namespaceProvider;
  private final SensitiveValueMasker masker;
  private final ObjectMapper objectMapper;

  public EventContextBuilder(
      EventRepository eventRepository,
      NodeExecutionRepository executionRepository,
      NodeDefinitionRepository nodeRepository,
      WorkflowVariableRepository variableRepository,
      WorkflowFormRepository formRepository,
      TicketContextSource ticketSource,
      ActorContextProvider actorProvider,
      EventContextNamespaceProvider namespaceProvider,
      SensitiveValueMasker masker,
      ObjectMapper objectMapper) {
    this.eventRepository = eventRepository;
    this.executionRepository = executionRepository;
    this.nodeRepository = nodeRepository;
    this.variableRepository = variableRepository;
    this.formRepository = formRepository;
    this.ticketSource = ticketSource;
    this.actorProvider = actorProvider;
    this.namespaceProvider = namespaceProvider;
    this.masker = masker;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public EventContext build(UUID eventId) {
    return build(eventId, RuntimeScope.event());
  }

  @Transactional(readOnly = true)
  public EventContext build(UUID eventId, RuntimeScope scope) {
    Objects.requireNonNull(scope, "scope");
    Event event =
        eventRepository
            .findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));
    TicketContextSnapshot ticket =
        ticketSource.load(event.getTicketId(), event.getStartedTicketRevisionId());
    List<WorkflowVariable> variables =
        variableRepository.findAllByWorkflowVersionIdOrderByKeyAsc(event.getWorkflowVersionId());
    List<NodeDefinition> nodes =
        nodeRepository.findAllByWorkflowVersionIdOrderByNodeKeyAsc(event.getWorkflowVersionId());
    List<NodeExecution> executions =
        executionRepository.findAllByEventIdOrderByCreatedAtAsc(event.getId());

    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.set("ticket", ticketNamespace(ticket));
    root.set("creator", namespaceProvider.creator(ticket));
    root.set("organization", namespaceProvider.organization(ticket));
    root.set("event", eventNamespace(event));
    root.set("variables", variableNamespace(event, variables));
    root.set("nodes", nodeNamespace(nodes, executions, scope));
    if (!scope.item().isEmpty()) root.set("item", scope.item());
    if (!scope.task().isEmpty()) root.set("task", scope.task());
    actorProvider.currentActor().ifPresent(actor -> root.set("actor", actorNamespace(actor)));

    Map<String, TypeDescriptor> paths = new HashMap<>();
    Set<String> repeating = new HashSet<>();
    addPlatformTypes(paths, ticket, event, scope);
    variables.forEach(variable -> paths.put("variables." + variable.getKey(), variable.getType()));
    addTicketFormTypes(event.getWorkflowVersionId(), paths);
    addNodeTypes(nodes, paths, repeating);
    scope.itemTypes().forEach((key, type) -> paths.put("item." + key, type));
    scope.taskTypes().forEach((key, type) -> paths.put("task." + key, type));

    List<SensitiveValueMetadata> sensitive = sensitiveMetadata(event, variables);
    return new EventContext(root, new ExpressionSchema(paths, repeating), sensitive, masker);
  }

  private ObjectNode ticketNamespace(TicketContextSnapshot ticket) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("id", ticket.ticketId().toString());
    result.put("requestTypeId", ticket.requestTypeId().toString());
    result.put("creatorId", ticket.creatorId().toString());
    result.put("status", ticket.status());
    result.put("dataRevision", ticket.dataRevision());
    // The unqualified data view is pinned to the revision with which the Event started.
    result.set("data", ticket.revisionData());
    result.set("subjects", subjects(ticket.subjects()));
    ObjectNode revision = result.putObject("revision");
    revision.put("id", ticket.revisionId().toString());
    revision.put("revisionNo", ticket.revisionNo());
    revision.put("sourceSchemaVersion", ticket.sourceSchemaVersion());
    revision.put("schemaChecksum", ticket.schemaChecksum());
    revision.put("submittedAt", ticket.revisionSubmittedAt().toString());
    revision.set("data", ticket.revisionData());
    ObjectNode current = result.putObject("current");
    if (ticket.currentRevisionId() != null) {
      current.put("revisionId", ticket.currentRevisionId().toString());
    }
    current.set("data", ticket.currentData());
    return result;
  }

  private ArrayNode subjects(List<TicketSubjectSnapshot> subjects) {
    ArrayNode result = JsonNodeFactory.instance.arrayNode();
    subjects.forEach(
        subject -> {
          ObjectNode item = result.addObject();
          item.put("type", subject.subjectType());
          item.put("referenceId", subject.subjectRefId().toString());
          item.put("role", subject.roleKey());
          if (subject.sourceField() != null) item.put("sourceField", subject.sourceField());
        });
    return result;
  }

  private ObjectNode eventNamespace(Event event) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("id", event.getId().toString());
    result.put("workflowVersionId", event.getWorkflowVersionId().toString());
    result.put("status", event.getStatus().name());
    result.put("type", event.getEventType().name());
    result.put("rootEventId", event.getRootEventId().toString());
    result.put("startedAt", event.getStartedAt().toString());
    if (event.getOutcome() != null) result.put("outcome", event.getOutcome());
    return result;
  }

  private ObjectNode variableNamespace(Event event, List<WorkflowVariable> declarations) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    Map<String, WorkflowVariable> byKey = new LinkedHashMap<>();
    declarations.forEach(
        variable -> {
          byKey.put(variable.getKey(), variable);
          if (variable.getDefaultJson() != null) {
            result.set(variable.getKey(), variable.getDefaultJson());
          }
        });
    JsonNode stored = event.getVariablesJson();
    stored
        .fields()
        .forEachRemaining(
            entry -> {
              WorkflowVariable declaration = byKey.get(entry.getKey());
              if (declaration == null) {
                throw new IllegalStateException(
                    "Event contains undeclared variable: " + entry.getKey());
              }
              CanonicalValueValidator.requireValid(declaration.getType(), entry.getValue());
              result.set(entry.getKey(), entry.getValue().deepCopy());
            });
    return result;
  }

  private ObjectNode nodeNamespace(
      List<NodeDefinition> nodes, List<NodeExecution> executions, RuntimeScope scope) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    Map<UUID, List<NodeExecution>> byDefinition = new HashMap<>();
    executions.forEach(
        execution ->
            byDefinition
                .computeIfAbsent(execution.getNodeDefinitionId(), ignored -> new ArrayList<>())
                .add(execution));
    for (NodeDefinition node : nodes) {
      List<NodeExecution> occurrences = byDefinition.getOrDefault(node.getId(), List.of());
      List<NodeExecution> visible =
          occurrences.stream().filter(it -> inScope(it, scope, true)).toList();
      List<NodeExecution> itemOccurrences =
          occurrences.stream().filter(it -> inScope(it, scope, false)).toList();
      ObjectNode nodeValue = result.putObject(node.getNodeKey());
      ArrayNode executionValues = nodeValue.putArray("executions");
      visible.forEach(execution -> executionValues.add(executionValue(execution)));
      latestWithOutput(visible).ifPresent(value -> nodeValue.set("latest", executionValue(value)));
      ObjectNode items = nodeValue.putObject("items");
      itemOccurrences.stream()
          .filter(execution -> execution.getItemToken() != null)
          .collect(
              java.util.stream.Collectors.groupingBy(
                  NodeExecution::getItemToken,
                  LinkedHashMap::new,
                  java.util.stream.Collectors.toList()))
          .forEach(
              (itemToken, values) -> {
                ArrayNode array = items.putArray(itemToken);
                values.forEach(value -> array.add(executionValue(value)));
              });
    }
    return result;
  }

  private boolean inScope(NodeExecution execution, RuntimeScope scope, boolean constrainItem) {
    if (scope.cycleId() != null && !scope.cycleId().equals(execution.getCycleId())) return false;
    if (scope.pathToken() != null && !pathVisible(scope.pathToken(), execution.getPathToken())) {
      return false;
    }
    return !constrainItem
        || scope.itemToken() == null
        || execution.getItemToken() == null
        || scope.itemToken().equals(execution.getItemToken());
  }

  private boolean pathVisible(String currentPath, String candidatePath) {
    return currentPath.equals(candidatePath) || currentPath.startsWith(candidatePath + "/");
  }

  private java.util.Optional<NodeExecution> latestWithOutput(List<NodeExecution> executions) {
    return executions.stream()
        .filter(execution -> execution.getOutputJson() != null)
        .max(Comparator.comparing(NodeExecution::getCreatedAt).thenComparing(NodeExecution::getId));
  }

  private ObjectNode executionValue(NodeExecution execution) {
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.put("id", execution.getId().toString());
    value.put("activationKey", execution.getActivationKey());
    value.put("status", execution.getStatus().name());
    value.put("cycleId", execution.getCycleId().toString());
    value.put("iteration", execution.getIteration());
    value.put("pathToken", execution.getPathToken());
    if (execution.getItemToken() != null) value.put("itemToken", execution.getItemToken());
    if (execution.getOutcomePort() != null) value.put("outcomePort", execution.getOutcomePort());
    if (execution.getInputJson() != null) value.set("input", execution.getInputJson());
    if (execution.getOutputJson() != null) value.set("output", execution.getOutputJson());
    if (execution.getErrorJson() != null) value.set("error", execution.getErrorJson());
    return value;
  }

  private ObjectNode actorNamespace(ActorContext actor) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("id", actor.actorId().toString());
    result.put("principal", actor.principalName());
    result.set("roles", objectMapper.valueToTree(actor.roles()));
    result.set("permissions", objectMapper.valueToTree(actor.permissions()));
    return result;
  }

  private void addPlatformTypes(
      Map<String, TypeDescriptor> paths,
      TicketContextSnapshot ticket,
      Event event,
      RuntimeScope scope) {
    paths.put("ticket.id", STRING);
    paths.put("ticket.requestTypeId", STRING);
    paths.put("ticket.creatorId", TypeDescriptor.required(CanonicalValueType.USER_ID));
    paths.put("ticket.status", STRING);
    paths.put("ticket.dataRevision", INTEGER);
    paths.put("ticket.subjects", TypeDescriptor.arrayOf(OBJECT));
    paths.put("creator.id", TypeDescriptor.required(CanonicalValueType.USER_ID));
    paths.put("event.id", STRING);
    paths.put("event.workflowVersionId", STRING);
    paths.put("event.status", STRING);
    paths.put("event.startedAt", TypeDescriptor.required(CanonicalValueType.DATETIME));
    if (actorProvider.currentActor().isPresent()) {
      paths.put("actor.id", TypeDescriptor.required(CanonicalValueType.USER_ID));
      paths.put("actor.principal", STRING);
      paths.put("actor.roles", TypeDescriptor.arrayOf(STRING));
      paths.put("actor.permissions", TypeDescriptor.arrayOf(STRING));
    }
  }

  private void addTicketFormTypes(UUID workflowVersionId, Map<String, TypeDescriptor> paths) {
    formRepository.findAllByWorkflowVersionIdOrderByFormKeyAsc(workflowVersionId).stream()
        .filter(form -> form.getFormType() == WorkflowFormType.TICKET_FORM)
        .forEach(
            form -> {
              try {
                FormSchema schema =
                    objectMapper.treeToValue(form.getSchemaJson(), FormSchema.class);
                schema
                    .fields()
                    .forEach(
                        field -> {
                          paths.put("ticket.data." + field.key(), field.type());
                          paths.put("ticket.revision.data." + field.key(), field.type());
                          paths.put("ticket.current.data." + field.key(), field.type());
                        });
              } catch (JsonProcessingException exception) {
                throw new IllegalStateException(
                    "Published ticket form cannot be decoded", exception);
              }
            });
  }

  private void addNodeTypes(
      List<NodeDefinition> nodes, Map<String, TypeDescriptor> paths, Set<String> repeating) {
    for (NodeDefinition node : nodes) {
      // Every node may execute repeatedly due to rework; unqualified output is therefore unsafe.
      repeating.add(node.getNodeKey());
      paths.put("nodes." + node.getNodeKey() + ".executions", TypeDescriptor.arrayOf(OBJECT));
      paths.put("nodes." + node.getNodeKey() + ".items", OBJECT);
      JsonNode schemaJson = node.getOutputSchemaJson();
      if (schemaJson == null) continue;
      try {
        CanonicalSchema schema = objectMapper.treeToValue(schemaJson, CanonicalSchema.class);
        schema
            .properties()
            .forEach(
                (key, type) -> {
                  paths.put("nodes." + node.getNodeKey() + ".output." + key, type);
                  paths.put("nodes." + node.getNodeKey() + ".latest.output." + key, type);
                });
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException(
            "Published node output schema cannot be decoded", exception);
      }
    }
  }

  private List<SensitiveValueMetadata> sensitiveMetadata(
      Event event, List<WorkflowVariable> variables) {
    List<SensitiveValueMetadata> sensitive = new ArrayList<>();
    variables.stream()
        .filter(WorkflowVariable::isSensitive)
        .forEach(
            variable ->
                sensitive.add(
                    new SensitiveValueMetadata(
                        "variables." + variable.getKey(), "WORKFLOW_VARIABLE", variable.getId())));
    formRepository
        .findAllByWorkflowVersionIdOrderByFormKeyAsc(event.getWorkflowVersionId())
        .stream()
        .filter(form -> form.getFormType() == WorkflowFormType.TICKET_FORM)
        .forEach(
            form -> {
              try {
                FormSchema schema =
                    objectMapper.treeToValue(form.getSchemaJson(), FormSchema.class);
                schema.fields().stream()
                    .filter(field -> field.sensitive())
                    .forEach(
                        field -> {
                          sensitive.add(
                              new SensitiveValueMetadata(
                                  "ticket.data." + field.key(), "FORM_FIELD", field.fieldId()));
                          sensitive.add(
                              new SensitiveValueMetadata(
                                  "ticket.revision.data." + field.key(),
                                  "FORM_FIELD",
                                  field.fieldId()));
                          sensitive.add(
                              new SensitiveValueMetadata(
                                  "ticket.current.data." + field.key(),
                                  "FORM_FIELD",
                                  field.fieldId()));
                        });
              } catch (JsonProcessingException exception) {
                throw new IllegalStateException(
                    "Published ticket form cannot be decoded", exception);
              }
            });
    return List.copyOf(sensitive);
  }
}
