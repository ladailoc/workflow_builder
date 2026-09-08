package com.fpt.workflow.form.engine;

import com.fpt.workflow.form.domain.WorkflowFormType;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record FormSchema(
    String formKey, WorkflowFormType formType, List<FormFieldDefinition> fields) {

  private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,127}");

  public FormSchema {
    if (formKey == null || !KEY.matcher(formKey).matches()) {
      throw new IllegalArgumentException("Invalid formKey");
    }
    formType = Objects.requireNonNull(formType, "formType");
    fields =
        List.copyOf(Objects.requireNonNull(fields, "fields")).stream()
            .sorted(Comparator.comparingInt(FormFieldDefinition::order))
            .toList();
  }

  public static FormSchema ticketForm(String formKey, List<FormFieldDefinition> fields) {
    return new FormSchema(formKey, WorkflowFormType.TICKET_FORM, fields);
  }

  public static FormSchema taskForm(String formKey, List<FormFieldDefinition> fields) {
    return new FormSchema(formKey, WorkflowFormType.TASK_FORM, fields);
  }
}
