package com.fpt.workflow.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.shared.domain.value.CanonicalSchema;
import com.fpt.workflow.shared.domain.value.CanonicalValidationResult;
import com.fpt.workflow.shared.domain.value.CanonicalValueType;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.shared.domain.value.TypeCompatibility;
import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CanonicalTypeSystemTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void exposesExactlyTheCanonicalTypeSet() {
    assertThat(CanonicalValueType.values())
        .containsExactly(
            CanonicalValueType.STRING,
            CanonicalValueType.NUMBER,
            CanonicalValueType.INTEGER,
            CanonicalValueType.BOOLEAN,
            CanonicalValueType.DATE,
            CanonicalValueType.DATETIME,
            CanonicalValueType.DURATION,
            CanonicalValueType.MONEY,
            CanonicalValueType.USER_ID,
            CanonicalValueType.USER,
            CanonicalValueType.DEPARTMENT_ID,
            CanonicalValueType.GROUP_ID,
            CanonicalValueType.ENUM,
            CanonicalValueType.OBJECT,
            CanonicalValueType.ARRAY,
            CanonicalValueType.FILE_REF,
            CanonicalValueType.FILE_LIST);
  }

  @Test
  void appliesWideningAndNullableAssignabilityRules() {
    TypeDescriptor integer = required(CanonicalValueType.INTEGER);
    TypeDescriptor number = required(CanonicalValueType.NUMBER);
    TypeDescriptor nullableNumber = TypeDescriptor.nullable(CanonicalValueType.NUMBER);

    assertThat(TypeCompatibility.isAssignable(integer, number)).isTrue();
    assertThat(TypeCompatibility.isAssignable(integer, nullableNumber)).isTrue();
    assertThat(TypeCompatibility.isAssignable(number, integer)).isFalse();
    assertThat(TypeCompatibility.isAssignable(nullableNumber, number)).isFalse();
    assertThat(
            TypeCompatibility.isAssignable(
                required(CanonicalValueType.USER_ID), required(CanonicalValueType.USER)))
        .isFalse();
  }

  @Test
  void modelsAndValidatesRecursiveArrayItemTypes() throws Exception {
    TypeDescriptor integerArray = TypeDescriptor.arrayOf(required(CanonicalValueType.INTEGER));
    TypeDescriptor numberArray = TypeDescriptor.arrayOf(required(CanonicalValueType.NUMBER));
    TypeDescriptor nullableStringArray =
        TypeDescriptor.arrayOf(TypeDescriptor.nullable(CanonicalValueType.STRING));

    assertThat(integerArray.displayName()).isEqualTo("ARRAY<INTEGER>");
    assertThat(TypeCompatibility.isAssignable(integerArray, numberArray)).isTrue();
    assertThat(valid(integerArray, "[1,2,3]")).isTrue();
    assertThat(valid(integerArray, "[1,2.5]")).isFalse();
    assertThat(valid(nullableStringArray, "[\"a\",null]")).isTrue();
    assertThatThrownBy(() -> TypeDescriptor.required(CanonicalValueType.ARRAY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itemType");
  }

  @Test
  void validatesCanonicalMoneyDateDatetimeAndDurationJson() throws Exception {
    assertThat(valid(required(CanonicalValueType.MONEY), "{\"amount\":12.50,\"currency\":\"USD\"}"))
        .isTrue();
    assertThat(valid(required(CanonicalValueType.MONEY), "{\"amount\":12.50,\"currency\":\"usd\"}"))
        .isFalse();
    assertThat(valid(required(CanonicalValueType.DATE), "\"2026-09-07\"")).isTrue();
    assertThat(valid(required(CanonicalValueType.DATE), "\"07/09/2026\"")).isFalse();
    assertThat(valid(required(CanonicalValueType.DATETIME), "\"2026-09-07T12:30:00Z\"")).isTrue();
    assertThat(valid(required(CanonicalValueType.DATETIME), "\"2026-09-07T12:30:00\"")).isFalse();
    assertThat(valid(required(CanonicalValueType.DURATION), "\"PT45M\"")).isTrue();
  }

  @Test
  void validatesReferenceTypesAndFileCollections() throws Exception {
    String id = "10000000-0000-4000-8000-000000000001";
    TypeDescriptor fileList = TypeDescriptor.fileList(false);
    TypeDescriptor genericFileArray = TypeDescriptor.arrayOf(required(CanonicalValueType.FILE_REF));

    assertThat(valid(required(CanonicalValueType.USER_ID), "\"" + id + "\"")).isTrue();
    assertThat(valid(required(CanonicalValueType.DEPARTMENT_ID), "\"department-1\"")).isFalse();
    assertThat(valid(required(CanonicalValueType.USER), "{\"id\":\"" + id + "\"}")).isTrue();
    assertThat(valid(required(CanonicalValueType.USER), "\"" + id + "\"")).isFalse();
    assertThat(valid(fileList, "[{\"id\":\"" + id + "\"}]")).isTrue();
    assertThat(TypeCompatibility.isAssignable(fileList, genericFileArray)).isTrue();
    assertThat(TypeCompatibility.isAssignable(genericFileArray, fileList)).isTrue();
  }

  @Test
  void validatesStrictCanonicalObjectSchemas() throws Exception {
    CanonicalSchema schema =
        CanonicalSchema.strict(
            Map.of(
                "name", required(CanonicalValueType.STRING),
                "departmentId", TypeDescriptor.nullable(CanonicalValueType.DEPARTMENT_ID)),
            Set.of("name"));

    assertThat(CanonicalValueValidator.validate(schema, json("{\"name\":\"Ada\"}")).valid())
        .isTrue();
    CanonicalValidationResult invalid =
        CanonicalValueValidator.validate(schema, json("{\"unexpected\":true}"));
    assertThat(invalid.issues())
        .extracting(issue -> issue.code())
        .containsExactlyInAnyOrder("SCHEMA.REQUIRED_PROPERTY_MISSING", "SCHEMA.UNKNOWN_PROPERTY");
  }

  @Test
  void serializesAndDeserializesOneStableDescriptorRepresentation() throws Exception {
    TypeDescriptor original =
        TypeDescriptor.nullableArrayOf(TypeDescriptor.nullable(CanonicalValueType.USER_ID));

    String json = objectMapper.writeValueAsString(original);
    TypeDescriptor restored = objectMapper.readValue(json, TypeDescriptor.class);

    assertThat(restored).isEqualTo(original);
    assertThat(json)
        .isEqualTo(
            "{\"type\":\"ARRAY\",\"nullable\":true,\"itemType\":{\"type\":\"USER_ID\",\"nullable\":true}}");
  }

  private boolean valid(TypeDescriptor descriptor, String json) throws Exception {
    return CanonicalValueValidator.validate(descriptor, json(json)).valid();
  }

  private JsonNode json(String value) throws Exception {
    return objectMapper.readTree(value);
  }

  private static TypeDescriptor required(CanonicalValueType type) {
    return TypeDescriptor.required(type);
  }
}
