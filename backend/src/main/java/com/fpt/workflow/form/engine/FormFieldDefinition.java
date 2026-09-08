package com.fpt.workflow.form.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record FormFieldDefinition(
    UUID fieldId,
    String key,
    String label,
    String description,
    String placeholder,
    int order,
    TypeDescriptor type,
    JsonNode defaultValue,
    boolean sensitive,
    FieldRequirement requirement,
    FieldVisibility visibility,
    FieldEditability editability,
    FieldValidationRules validation,
    FieldOptions options,
    FieldSemanticMetadata semantics) {

  private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,127}");

  public FormFieldDefinition {
    fieldId = Objects.requireNonNull(fieldId, "fieldId");
    if (key == null || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Invalid field key");
    }
    if (order < 0) {
      throw new IllegalArgumentException("Field order must not be negative");
    }
    type = Objects.requireNonNull(type, "type");
    defaultValue = defaultValue == null || defaultValue.isNull() ? null : defaultValue.deepCopy();
    requirement = Objects.requireNonNull(requirement, "requirement");
    visibility = Objects.requireNonNull(visibility, "visibility");
    editability = Objects.requireNonNull(editability, "editability");
    validation = Objects.requireNonNull(validation, "validation");
    semantics = Objects.requireNonNull(semantics, "semantics");
  }
}
