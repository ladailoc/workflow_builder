package com.fpt.workflow.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.domain.WorkflowFormType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportableFieldCatalogTest {

  @Test
  void exposesOnlySchemaOptedInFields() throws Exception {
    var json =
        new ObjectMapper()
            .readTree(
                """
                {"fields":[
                  {"key":"amount","type":"MONEY","filterable":true,"reportable":true},
                  {"key":"secretNote","type":"STRING","sensitive":true},
                  {"key":"reference","type":"STRING","searchable":true}
                ]}
                """);
    WorkflowForm form =
        WorkflowForm.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ticket",
            WorkflowFormType.TICKET_FORM,
            json,
            "checksum");

    assertThat(new ReportableFieldCatalog().fields(form))
        .extracting(ReportableFieldCatalog.FieldProjection::fieldKey)
        .containsExactly("amount", "reference");
  }

  @Test
  void returnsEmptyWhenNoFieldsOptedIn() throws Exception {
    var json =
        new ObjectMapper()
            .readTree(
                """
                {"fields":[
                  {"key":"description","type":"STRING"},
                  {"key":"internalComments","type":"STRING","sensitive":true}
                ]}
                """);
    WorkflowForm form =
        WorkflowForm.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ticket",
            WorkflowFormType.TICKET_FORM,
            json,
            "checksum");

    assertThat(new ReportableFieldCatalog().fields(form)).isEmpty();
  }

  @Test
  void returnsEmptyWhenSchemaLacksFieldsArray() throws Exception {
    var json = new ObjectMapper().readTree("{\"type\":\"object\"}");
    WorkflowForm form =
        WorkflowForm.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "ticket",
            WorkflowFormType.TICKET_FORM,
            json,
            "checksum");

    assertThat(new ReportableFieldCatalog().fields(form)).isEmpty();
  }
}
