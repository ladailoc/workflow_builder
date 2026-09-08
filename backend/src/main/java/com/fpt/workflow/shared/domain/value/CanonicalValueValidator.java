package com.fpt.workflow.shared.domain.value;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates canonical JSON values without feature-specific type interpretations. */
public final class CanonicalValueValidator {

  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern UUID_TEXT =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

  private CanonicalValueValidator() {}

  public static CanonicalValidationResult validate(TypeDescriptor descriptor, JsonNode value) {
    Objects.requireNonNull(descriptor, "descriptor");
    List<CanonicalValidationIssue> issues = new ArrayList<>();
    validateValue(descriptor, value, "$", issues);
    return new CanonicalValidationResult(issues);
  }

  public static CanonicalValidationResult validate(CanonicalSchema schema, JsonNode value) {
    Objects.requireNonNull(schema, "schema");
    List<CanonicalValidationIssue> issues = new ArrayList<>();
    if (value == null || value.isNull() || !value.isObject()) {
      issue(issues, "TYPE.OBJECT_REQUIRED", "$", "Expected an object");
      return new CanonicalValidationResult(issues);
    }

    for (String required : schema.requiredProperties()) {
      if (!value.has(required)) {
        issue(
            issues,
            "SCHEMA.REQUIRED_PROPERTY_MISSING",
            "$." + required,
            "Required property is missing");
      }
    }
    schema
        .properties()
        .forEach(
            (name, descriptor) -> {
              if (value.has(name)) {
                validateValue(descriptor, value.get(name), "$." + name, issues);
              }
            });
    if (!schema.additionalProperties()) {
      Set<String> declared = schema.properties().keySet();
      Iterator<String> names = value.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (!declared.contains(name)) {
          issue(
              issues,
              "SCHEMA.UNKNOWN_PROPERTY",
              "$." + name,
              "Property is not declared by the schema");
        }
      }
    }
    return new CanonicalValidationResult(issues);
  }

  public static void requireValid(TypeDescriptor descriptor, JsonNode value) {
    validate(descriptor, value).requireValid();
  }

  private static void validateValue(
      TypeDescriptor descriptor,
      JsonNode value,
      String path,
      List<CanonicalValidationIssue> issues) {
    if (value == null || value.isNull()) {
      if (!descriptor.nullable()) {
        issue(issues, "TYPE.NULL_NOT_ALLOWED", path, "Null is not assignable to this type");
      }
      return;
    }
    if (descriptor.isCollection()) {
      validateCollection(descriptor, value, path, issues);
      return;
    }

    switch (descriptor.type()) {
      case STRING ->
          require(value.isTextual(), "TYPE.STRING_REQUIRED", path, "Expected a string", issues);
      case NUMBER ->
          require(value.isNumber(), "TYPE.NUMBER_REQUIRED", path, "Expected a number", issues);
      case INTEGER ->
          require(
              value.isIntegralNumber(),
              "TYPE.INTEGER_REQUIRED",
              path,
              "Expected an integer",
              issues);
      case BOOLEAN ->
          require(value.isBoolean(), "TYPE.BOOLEAN_REQUIRED", path, "Expected a boolean", issues);
      case DATE -> validateDate(value, path, issues);
      case DATETIME -> validateDateTime(value, path, issues);
      case DURATION -> validateDuration(value, path, issues);
      case MONEY -> validateMoney(value, path, issues);
      case USER_ID, DEPARTMENT_ID, GROUP_ID -> validateUuidText(value, path, issues);
      case USER -> validateReferenceObject(value, path, "USER", issues);
      case ENUM ->
          require(value.isTextual(), "TYPE.ENUM_REQUIRED", path, "Expected an enum string", issues);
      case OBJECT ->
          require(value.isObject(), "TYPE.OBJECT_REQUIRED", path, "Expected an object", issues);
      case FILE_REF -> validateReferenceObject(value, path, "FILE_REF", issues);
      case ARRAY, FILE_LIST ->
          throw new IllegalStateException("Collection validation was not dispatched");
    }
  }

  private static void validateCollection(
      TypeDescriptor descriptor,
      JsonNode value,
      String path,
      List<CanonicalValidationIssue> issues) {
    if (!value.isArray()) {
      issue(issues, "TYPE.ARRAY_REQUIRED", path, "Expected an array");
      return;
    }
    TypeDescriptor itemType = descriptor.itemType();
    for (int index = 0; index < value.size(); index++) {
      validateValue(itemType, value.get(index), path + "[" + index + "]", issues);
    }
  }

  private static void validateDate(
      JsonNode value, String path, List<CanonicalValidationIssue> issues) {
    if (!value.isTextual()) {
      issue(issues, "TYPE.DATE_REQUIRED", path, "Expected an ISO-8601 local date string");
      return;
    }
    try {
      LocalDate.parse(value.textValue());
    } catch (DateTimeParseException exception) {
      issue(issues, "TYPE.DATE_INVALID", path, "Expected an ISO-8601 local date string");
    }
  }

  private static void validateDateTime(
      JsonNode value, String path, List<CanonicalValidationIssue> issues) {
    if (!value.isTextual()) {
      issue(issues, "TYPE.DATETIME_REQUIRED", path, "Expected an UTC ISO-8601 instant string");
      return;
    }
    try {
      Instant.parse(value.textValue());
    } catch (DateTimeParseException exception) {
      issue(issues, "TYPE.DATETIME_INVALID", path, "Expected an UTC ISO-8601 instant string");
    }
  }

  private static void validateDuration(
      JsonNode value, String path, List<CanonicalValidationIssue> issues) {
    if (!value.isTextual()) {
      issue(issues, "TYPE.DURATION_REQUIRED", path, "Expected an ISO-8601 duration string");
      return;
    }
    try {
      Duration.parse(value.textValue());
    } catch (DateTimeParseException exception) {
      issue(issues, "TYPE.DURATION_INVALID", path, "Expected an ISO-8601 duration string");
    }
  }

  private static void validateMoney(
      JsonNode value, String path, List<CanonicalValidationIssue> issues) {
    if (!value.isObject()) {
      issue(issues, "TYPE.MONEY_REQUIRED", path, "Expected {amount:number,currency:string}");
      return;
    }
    Set<String> fields = new HashSet<>();
    value.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(Set.of("amount", "currency"))
        || !value.path("amount").isNumber()
        || !value.path("currency").isTextual()
        || !CURRENCY.matcher(value.path("currency").textValue()).matches()) {
      issue(
          issues,
          "TYPE.MONEY_INVALID",
          path,
          "Money requires only a numeric amount and uppercase three-letter currency");
    }
  }

  private static void validateReferenceObject(
      JsonNode value, String path, String type, List<CanonicalValidationIssue> issues) {
    if (!value.isObject() || !isUuidText(value.get("id"))) {
      issue(issues, "TYPE." + type + "_INVALID", path, type + " requires an object with a UUID id");
    }
  }

  private static void validateUuidText(
      JsonNode value, String path, List<CanonicalValidationIssue> issues) {
    if (!isUuidText(value)) {
      issue(issues, "TYPE.REFERENCE_ID_INVALID", path, "Expected a UUID string");
    }
  }

  private static boolean isUuidText(JsonNode value) {
    if (value == null || !value.isTextual() || !UUID_TEXT.matcher(value.textValue()).matches()) {
      return false;
    }
    UUID.fromString(value.textValue());
    return true;
  }

  private static void require(
      boolean condition,
      String code,
      String path,
      String message,
      List<CanonicalValidationIssue> issues) {
    if (!condition) {
      issue(issues, code, path, message);
    }
  }

  private static void issue(
      List<CanonicalValidationIssue> issues, String code, String path, String message) {
    issues.add(new CanonicalValidationIssue(code, path, message));
  }
}
