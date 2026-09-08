package com.fpt.workflow.definition.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.domain.WorkflowFormType;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowContractDomainTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void exposesCanonicalContractEnums() {
    assertThat(WorkflowFormType.values())
        .containsExactly(WorkflowFormType.TICKET_FORM, WorkflowFormType.TASK_FORM);
    assertThat(ValidationSeverity.values())
        .containsExactly(
            ValidationSeverity.ERROR,
            ValidationSeverity.WARNING,
            ValidationSeverity.ACK_REQUIRED_WARNING,
            ValidationSeverity.INFO);
    assertThat(VariableScope.values())
        .containsExactly(
            VariableScope.EVENT, VariableScope.NODE, VariableScope.MULTI_INSTANCE_ITEM);
    assertThat(CanonicalValueType.values())
        .contains(
            CanonicalValueType.STRING, CanonicalValueType.MONEY, CanonicalValueType.FILE_LIST);
  }

  @Test
  void rejectsInvalidFormAndValidationContractsBeforePersistence() {
    assertThatThrownBy(
            () ->
                WorkflowForm.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "ticket",
                    WorkflowFormType.TICKET_FORM,
                    objectMapper.createArrayNode(),
                    "sha256"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JSON object");

    assertThatThrownBy(
            () ->
                WorkflowValidationRun.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    0,
                    "sha256",
                    false,
                    true,
                    0,
                    0,
                    0,
                    UUID.randomUUID(),
                    Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid");

    assertThatThrownBy(
            () ->
                WorkflowValidationIssue.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "FORM.INVALID",
                    ValidationSeverity.ERROR,
                    "WORKFLOW_FORM",
                    UUID.randomUUID(),
                    null,
                    "Invalid form",
                    null,
                    objectMapper.createArrayNode()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JSON object");
  }
}
