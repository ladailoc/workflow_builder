package com.fpt.workflow.shared.domain.value;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.Optional;

/** Recursive canonical type descriptor shared by every configurable platform capability. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TypeDescriptor(
    @JsonProperty("type") CanonicalValueType type,
    @JsonProperty("nullable") boolean nullable,
    @JsonProperty("itemType") TypeDescriptor itemType) {

  @JsonCreator
  public TypeDescriptor {
    type = Objects.requireNonNull(type, "type");
    if (type == CanonicalValueType.ARRAY && itemType == null) {
      throw new IllegalArgumentException("ARRAY requires an itemType");
    }
    if (type == CanonicalValueType.FILE_LIST) {
      TypeDescriptor fileRef = required(CanonicalValueType.FILE_REF);
      if (itemType != null && !itemType.equals(fileRef)) {
        throw new IllegalArgumentException("FILE_LIST itemType must be non-nullable FILE_REF");
      }
      itemType = fileRef;
    } else if (type != CanonicalValueType.ARRAY && itemType != null) {
      throw new IllegalArgumentException(type + " cannot declare an itemType");
    }
  }

  public static TypeDescriptor required(CanonicalValueType type) {
    return new TypeDescriptor(type, false, null);
  }

  public static TypeDescriptor nullable(CanonicalValueType type) {
    return new TypeDescriptor(type, true, null);
  }

  public static TypeDescriptor arrayOf(TypeDescriptor itemType) {
    return new TypeDescriptor(CanonicalValueType.ARRAY, false, itemType);
  }

  public static TypeDescriptor nullableArrayOf(TypeDescriptor itemType) {
    return new TypeDescriptor(CanonicalValueType.ARRAY, true, itemType);
  }

  public static TypeDescriptor fileList(boolean nullable) {
    return new TypeDescriptor(CanonicalValueType.FILE_LIST, nullable, null);
  }

  public TypeDescriptor withNullable(boolean nullable) {
    return new TypeDescriptor(type, nullable, itemType);
  }

  @JsonIgnore
  public boolean isCollection() {
    return type == CanonicalValueType.ARRAY || type == CanonicalValueType.FILE_LIST;
  }

  public Optional<TypeDescriptor> collectionItemType() {
    return Optional.ofNullable(itemType);
  }

  public String displayName() {
    String name =
        type == CanonicalValueType.ARRAY ? "ARRAY<" + itemType.displayName() + ">" : type.name();
    return nullable ? name + "?" : name;
  }
}
