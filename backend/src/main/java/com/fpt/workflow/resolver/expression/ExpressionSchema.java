package com.fpt.workflow.resolver.expression;

import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Static path catalog built from form, variable, node-output, and platform context schemas. */
public record ExpressionSchema(
    Map<String, TypeDescriptor> pathTypes, Set<String> repeatingNodeKeys) {

  public ExpressionSchema {
    Objects.requireNonNull(pathTypes, "pathTypes");
    TreeMap<String, TypeDescriptor> normalized = new TreeMap<>();
    pathTypes.forEach(
        (path, type) -> {
          String normalizedPath = new ReferencePath(path).value();
          normalized.put(normalizedPath, Objects.requireNonNull(type, "path type"));
        });
    pathTypes = Map.copyOf(normalized);
    repeatingNodeKeys = Set.copyOf(Objects.requireNonNull(repeatingNodeKeys, "repeatingNodeKeys"));
  }

  public Optional<TypeDescriptor> typeOf(ReferencePath path) {
    return Optional.ofNullable(pathTypes.get(path.value()));
  }

  public boolean isAmbiguousRepeatedNodeReference(ReferencePath path) {
    var segments = path.segments();
    if (segments.size() < 3
        || !segments.get(0).equals("nodes")
        || !repeatingNodeKeys.contains(segments.get(1))) {
      return false;
    }
    return !Set.of("latest", "executions", "items", "cycles").contains(segments.get(2));
  }
}
