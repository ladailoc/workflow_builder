package com.fpt.workflow.shared.domain.value;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Canonical object schema composed exclusively from shared type descriptors. */
public record CanonicalSchema(
    @JsonProperty("properties") Map<String, TypeDescriptor> properties,
    @JsonProperty("requiredProperties") Set<String> requiredProperties,
    @JsonProperty("additionalProperties") boolean additionalProperties) {

  @JsonCreator
  public CanonicalSchema {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(requiredProperties, "requiredProperties");
    TreeMap<String, TypeDescriptor> normalizedProperties = new TreeMap<>();
    properties.forEach(
        (key, descriptor) -> {
          if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Schema property names must not be blank");
          }
          normalizedProperties.put(key, Objects.requireNonNull(descriptor, "property descriptor"));
        });
    TreeSet<String> normalizedRequired = new TreeSet<>(requiredProperties);
    if (!normalizedProperties.keySet().containsAll(normalizedRequired)) {
      throw new IllegalArgumentException("Required properties must be declared in properties");
    }
    properties = Collections.unmodifiableMap(normalizedProperties);
    requiredProperties = Collections.unmodifiableSet(normalizedRequired);
  }

  public static CanonicalSchema strict(
      Map<String, TypeDescriptor> properties, Set<String> requiredProperties) {
    return new CanonicalSchema(properties, requiredProperties, false);
  }

  public static CanonicalSchema open(
      Map<String, TypeDescriptor> properties, Set<String> requiredProperties) {
    return new CanonicalSchema(properties, requiredProperties, true);
  }

  public static CanonicalSchema open() {
    return new CanonicalSchema(Map.of(), Set.of(), true);
  }
}
