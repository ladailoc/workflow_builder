package com.fpt.workflow.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.form.engine.DynamicFormEngine;
import com.fpt.workflow.form.engine.FormIssueSeverity;
import com.fpt.workflow.form.engine.FormSchema;
import com.fpt.workflow.form.engine.FormValidationIssue;
import com.fpt.workflow.resolver.expression.ExpressionSchema;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.shared.api.UnprocessableCommandException;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import com.fpt.workflow.task.domain.TaskExecution;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Validates submitted values against the immutable FormVersion captured on a task. */
@Service
public class TaskFormValidationService {

  private final DynamicFormEngine engine;
  private final ObjectMapper mapper;

  public TaskFormValidationService(DynamicFormEngine engine, ObjectMapper mapper) {
    this.engine = Objects.requireNonNull(engine, "engine");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  public void validate(TaskExecution task, JsonNode submittedValues, ActorContext actor) {
    JsonNode schemaJson = task.getFormSchemaJson();
    if (schemaJson == null) {
      return;
    }
    FormSchema schema;
    try {
      schema = mapper.treeToValue(schemaJson, FormSchema.class);
    } catch (JsonProcessingException | IllegalArgumentException invalidSchema) {
      throw new IllegalStateException(
          "Task " + task.getId() + " contains an invalid pinned FormVersion schema", invalidSchema);
    }
    JsonNode values = submittedValues == null ? JsonNodeFactory.instance.objectNode() : submittedValues;
    var context = JsonNodeFactory.instance.objectNode();
    context.putObject("actor").put("id", actor.actorId().toString());
    ExpressionSchema contextSchema =
        new ExpressionSchema(
            Map.of(
                "actor.id",
                TypeDescriptor.required(CanonicalValueType.USER_ID)),
            Set.of());
    List<FormValidationIssue> blocking =
        engine.validateSubmission(schema, values, context, contextSchema).issues().stream()
            .filter(issue -> issue.severity() == FormIssueSeverity.ERROR)
            .toList();
    if (!blocking.isEmpty()) {
      String summary =
          blocking.stream()
              .limit(5)
              .map(issue -> {
                return issue.code() + "@" + issue.fieldPath();
              })
              .collect(Collectors.joining(", "));
      throw new UnprocessableCommandException(
          "TASK_FORM_VALIDATION_FAILED", "Task data violates the pinned FormVersion: " + summary);
    }
  }
}
