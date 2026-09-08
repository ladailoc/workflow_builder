package com.fpt.workflow.ticket.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.RequestType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.engine.DynamicFormEngine;
import com.fpt.workflow.form.engine.FormIssueSeverity;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.form.engine.FormValidationIssue;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.resolver.expression.ExpressionSchema;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.api.UnprocessableCommandException;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Resolves and enforces the server-owned TicketForm contract for Ticket writes. */
@Service
public class TicketFormValidationService {

  private final WorkflowDefinitionRepository definitionRepository;
  private final WorkflowFormRepository formRepository;
  private final DynamicFormEngine formEngine;
  private final ObjectMapper objectMapper;

  public TicketFormValidationService(
      WorkflowDefinitionRepository definitionRepository,
      WorkflowFormRepository formRepository,
      DynamicFormEngine formEngine,
      ObjectMapper objectMapper) {
    this.definitionRepository = definitionRepository;
    this.formRepository = formRepository;
    this.formEngine = formEngine;
    this.objectMapper = objectMapper;
  }

  public TicketFormContract validateDraft(
      RequestType requestType, JsonNode data, ActorContext actor) {
    TicketFormContract contract = resolve(requestType);
    requireValid(contract, requestType, data, actor, true);
    return contract;
  }

  public TicketFormContract validateSubmission(
      RequestType requestType,
      JsonNode data,
      ActorContext actor,
      UUID expectedWorkflowVersionId,
      String expectedSchemaChecksum) {
    TicketFormContract contract = resolve(requestType);
    boolean changed = !contract.workflowVersionId().equals(expectedWorkflowVersionId);
    if (!changed && !contract.schemaChecksum().equals(expectedSchemaChecksum)) {
      throw new CommandConflictException(
          "FORM_SCHEMA_CHANGED",
          "The current Published TicketForm differs from the form loaded by the client");
    }
    if (changed && !matchesHistoricalContract(expectedWorkflowVersionId, expectedSchemaChecksum)) {
      throw new CommandConflictException(
          "TICKET_SCHEMA_OUTDATED", "The submitted source form contract is unknown or stale");
    }
    try {
      requireValid(contract, requestType, data, actor, false);
    } catch (UnprocessableCommandException exception) {
      if (changed) {
        throw new CommandConflictException(
            "FORM_SCHEMA_CHANGED",
            "The current Published TicketForm requires data not present in the loaded form");
      }
      throw exception;
    }
    return contract;
  }

  private boolean matchesHistoricalContract(UUID workflowVersionId, String checksum) {
    if (workflowVersionId == null || checksum == null) {
      return false;
    }
    return formRepository.findAllByWorkflowVersionIdOrderByFormKeyAsc(workflowVersionId).stream()
        .filter(form -> form.getFormType() == WorkflowFormType.TICKET_FORM)
        .anyMatch(form -> form.getSchemaChecksum().equals(checksum));
  }

  public TicketFormContract currentContract(RequestType requestType) {
    return resolve(requestType);
  }

  private TicketFormContract resolve(RequestType requestType) {
    Objects.requireNonNull(requestType, "requestType");
    WorkflowDefinition definition =
        definitionRepository
            .findById(requestType.getWorkflowDefinitionId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "WORKFLOW_DEFINITION_NOT_FOUND", "Workflow definition was not found"));
    if (definition.getLifecycle() != WorkflowDefinitionLifecycle.ACTIVE
        || definition.getCurrentPublishedVersionId() == null) {
      throw new CommandConflictException(
          "REQUEST_TYPE_NOT_PUBLISHABLE",
          "The Request Type has no active Published WorkflowVersion");
    }
    UUID workflowVersionId = definition.getCurrentPublishedVersionId();
    List<WorkflowForm> ticketForms =
        formRepository.findAllByWorkflowVersionIdOrderByFormKeyAsc(workflowVersionId).stream()
            .filter(form -> form.getFormType() == WorkflowFormType.TICKET_FORM)
            .toList();
    if (ticketForms.size() != 1) {
      throw new CommandConflictException(
          "TICKET_FORM_CONTRACT_INVALID",
          "A Published WorkflowVersion must expose exactly one TicketForm");
    }
    WorkflowForm stored = ticketForms.getFirst();
    FormSchema schema = decode(stored);
    if (schema.formType() != WorkflowFormType.TICKET_FORM
        || !schema.formKey().equals(stored.getFormKey())) {
      throw new IllegalStateException("Stored TicketForm identity does not match schema_json");
    }
    return new TicketFormContract(workflowVersionId, stored.getSchemaChecksum(), schema);
  }

  private FormSchema decode(WorkflowForm stored) {
    try {
      return objectMapper.treeToValue(stored.getSchemaJson(), FormSchema.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Published TicketForm schema_json is invalid", exception);
    }
  }

  private void requireValid(
      TicketFormContract contract,
      RequestType requestType,
      JsonNode data,
      ActorContext actor,
      boolean allowMissingRequired) {
    ObjectNode context = objectMapper.createObjectNode();
    context.putObject("actor").put("id", actor.actorId().toString());
    context.putObject("requestType").put("key", requestType.getKey());
    context.putObject("organization");
    ExpressionSchema contextSchema =
        new ExpressionSchema(
            Map.of(
                "actor.id", TypeDescriptor.required(CanonicalValueType.USER_ID),
                "requestType.key", TypeDescriptor.required(CanonicalValueType.STRING)),
            Set.of());
    List<FormValidationIssue> blocking =
        formEngine
            .validateSubmission(contract.schema(), data, context, contextSchema)
            .issues()
            .stream()
            .filter(issue -> issue.severity() == FormIssueSeverity.ERROR)
            .filter(
                issue ->
                    !allowMissingRequired || !issue.code().equals("FORM.REQUIRED_FIELD_MISSING"))
            .toList();
    if (!blocking.isEmpty()) {
      String summary =
          blocking.stream()
              .limit(5)
              .map(issue -> issue.code() + "@" + issue.fieldPath())
              .collect(Collectors.joining(", "));
      throw new UnprocessableCommandException(
          "TICKET_FORM_VALIDATION_FAILED", "Ticket data violates the Published form: " + summary);
    }
  }

  public record TicketFormContract(
      UUID workflowVersionId, String schemaChecksum, FormSchema schema) {
    public TicketFormContract {
      Objects.requireNonNull(workflowVersionId, "workflowVersionId");
      Objects.requireNonNull(schemaChecksum, "schemaChecksum");
      Objects.requireNonNull(schema, "schema");
    }
  }
}
