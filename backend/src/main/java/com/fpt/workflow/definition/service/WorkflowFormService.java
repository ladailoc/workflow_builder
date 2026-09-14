package com.fpt.workflow.definition.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.StaleDraftRevisionException;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.dto.WorkflowGraphDtos;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.form.dto.WorkflowFormDtos;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.api.CommandConflictException;
import com.fpt.workflow.shared.api.ResourceNotFoundException;
import com.fpt.workflow.shared.api.UnprocessableCommandException;
import com.fpt.workflow.shared.domain.AggregateVersion;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.OptimisticVersionGuard;
import com.fpt.workflow.shared.transaction.TransactionalCommand;
import com.fpt.workflow.shared.transaction.TransactionalQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** Draft-only management of the canonical Ticket Form contract. */
@Service
public class WorkflowFormService {

  private final WorkflowVersionRepository versionRepository;
  private final WorkflowFormRepository formRepository;
  private final ObjectMapper objectMapper;
  private final CanonicalDefinitionJson canonicalJson;
  private final UuidGenerator uuids;

  public WorkflowFormService(
      WorkflowVersionRepository versionRepository,
      WorkflowFormRepository formRepository,
      ObjectMapper objectMapper,
      CanonicalDefinitionJson canonicalJson,
      UuidGenerator uuids) {
    this.versionRepository = versionRepository;
    this.formRepository = formRepository;
    this.objectMapper = objectMapper;
    this.canonicalJson = canonicalJson;
    this.uuids = uuids;
  }

  @TransactionalQuery
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'OPERATOR', 'ADMIN')")
  public List<WorkflowFormDtos.View> list(UUID workflowVersionId) {
    requireVersion(workflowVersionId);
    return formRepository.findAllByWorkflowVersionIdOrderByFormKeyAsc(workflowVersionId).stream()
        .map(WorkflowFormDtos.View::from)
        .toList();
  }

  @TransactionalCommand
  @PreAuthorize("hasAnyRole('WORKFLOW_OWNER', 'WORKFLOW_EDITOR', 'ADMIN')")
  public FormMutation saveTicketForm(
      UUID workflowVersionId,
      JsonNode schemaJson,
      ExpectedVersion expectedVersion,
      long expectedRevision) {
    WorkflowVersion version = requireVersion(workflowVersionId);
    OptimisticVersionGuard.requireMatch(
        new AggregateVersion(version.getLockVersion()), expectedVersion);
    try {
      version.recordGraphMutation(expectedRevision);
    } catch (StaleDraftRevisionException exception) {
      throw new CommandConflictException(
          "WORKFLOW_DRAFT_REVISION_CONFLICT", exception.getMessage());
    } catch (IllegalStateException exception) {
      throw new CommandConflictException("WORKFLOW_VERSION_NOT_EDITABLE", exception.getMessage());
    }

    JsonNode canonical = canonicalJson.canonicalize(schemaJson);
    FormSchema decoded = decode(canonical);
    if (decoded.formType() != WorkflowFormType.TICKET_FORM) {
      throw new UnprocessableCommandException(
          "TICKET_FORM_TYPE_REQUIRED", "The managed request form must be a TICKET_FORM");
    }
    String checksum = checksum(canonical);
    WorkflowForm form =
        formRepository
            .findByWorkflowVersionIdAndFormKey(workflowVersionId, decoded.formKey())
            .map(
                existing -> {
                  existing.update(WorkflowFormType.TICKET_FORM, canonical, checksum);
                  return existing;
                })
            .orElseGet(
                () ->
                    WorkflowForm.create(
                        uuids.generate(),
                        workflowVersionId,
                        decoded.formKey(),
                        WorkflowFormType.TICKET_FORM,
                        canonical,
                        checksum));
    WorkflowForm savedForm = formRepository.saveAndFlush(form);
    WorkflowVersion savedVersion = versionRepository.saveAndFlush(version);
    return new FormMutation(
        WorkflowFormDtos.View.from(savedForm), WorkflowGraphDtos.DraftState.from(savedVersion));
  }

  private FormSchema decode(JsonNode schemaJson) {
    try {
      return objectMapper.treeToValue(schemaJson, FormSchema.class);
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new UnprocessableCommandException(
          "INVALID_FORM_SCHEMA", "Request form must use the canonical FormSchema contract");
    }
  }

  private String checksum(JsonNode canonical) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }

  private WorkflowVersion requireVersion(UUID workflowVersionId) {
    return versionRepository
        .findById(workflowVersionId)
        .orElseThrow(
            () ->
                new ResourceNotFoundException(
                    "WORKFLOW_VERSION_NOT_FOUND", "Workflow version was not found"));
  }

  public record FormMutation(WorkflowFormDtos.View form, WorkflowGraphDtos.DraftState draft) {}
}
