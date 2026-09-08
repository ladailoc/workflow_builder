package com.fpt.workflow.definition.dependency;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class FieldDependencyAnalyzer {

  public DependencyReport analyze(FieldChange change, List<DependencyResource> resources) {
    Objects.requireNonNull(change, "change");
    Objects.requireNonNull(resources, "resources");
    Pattern referencePattern = referencePattern(change.fieldKey());
    List<FieldDependency> dependencies = new ArrayList<>();
    for (DependencyResource resource : resources) {
      scan(
          change,
          resource,
          resource.content(),
          resource.fieldPath(),
          referencePattern,
          dependencies);
    }
    dependencies.sort(
        Comparator.comparing((FieldDependency item) -> item.resourceType().name())
            .thenComparing(FieldDependency::resourceId)
            .thenComparing(FieldDependency::fieldPath));
    return new DependencyReport(change, dependencies);
  }

  private void scan(
      FieldChange change,
      DependencyResource resource,
      JsonNode node,
      String path,
      Pattern pattern,
      List<FieldDependency> dependencies) {
    if (node.isTextual() && pattern.matcher(node.textValue()).find()) {
      dependencies.add(dependency(change, resource, path));
      return;
    }
    if (node.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        scan(
            change,
            resource,
            field.getValue(),
            path + "/" + escapePointer(field.getKey()),
            pattern,
            dependencies);
      }
    } else if (node.isArray()) {
      for (int index = 0; index < node.size(); index++) {
        scan(change, resource, node.get(index), path + "/" + index, pattern, dependencies);
      }
    }
  }

  private FieldDependency dependency(FieldChange change, DependencyResource resource, String path) {
    DependencyImpact impact =
        change.kind() == FieldChangeKind.TYPE_CHANGE
            ? DependencyImpact.TYPE_REVALIDATION_REQUIRED
            : DependencyImpact.BREAKING_REFERENCE;
    return new FieldDependency(
        resource.resourceType(),
        resource.resourceId(),
        path,
        classify(resource.resourceType(), path),
        DependencySeverity.ERROR,
        impact);
  }

  private DependencyUsageType classify(DependencyResourceType resourceType, String path) {
    String normalized = path.toLowerCase();
    if (resourceType == DependencyResourceType.EDGE && normalized.contains("condition")) {
      return DependencyUsageType.EDGE_CONDITION;
    }
    if (normalized.contains("inputbinding") || normalized.contains("input_binding")) {
      return DependencyUsageType.INPUT_BINDING;
    }
    if (normalized.contains("participant") || normalized.contains("resolver")) {
      return DependencyUsageType.PARTICIPANT_RESOLVER;
    }
    if (normalized.contains("multiinstance") || normalized.contains("multi_instance")) {
      return normalized.contains("subject")
          ? DependencyUsageType.MULTI_INSTANCE_SUBJECT
          : DependencyUsageType.MULTI_INSTANCE_COLLECTION;
    }
    if (normalized.contains("notification")) {
      return DependencyUsageType.NOTIFICATION;
    }
    if (normalized.contains("integration")
        || normalized.contains("connector")
        || normalized.contains("mapping")) {
      return DependencyUsageType.INTEGRATION_MAPPING;
    }
    if (resourceType == DependencyResourceType.VARIABLE || normalized.contains("variable")) {
      return DependencyUsageType.VARIABLE;
    }
    if (resourceType == DependencyResourceType.FORM) {
      return DependencyUsageType.FORM;
    }
    return DependencyUsageType.OTHER_TYPED_REFERENCE;
  }

  private Pattern referencePattern(String fieldKey) {
    String key = Pattern.quote(fieldKey);
    return Pattern.compile(
        "(?<![A-Za-z0-9_])(?:form|ticket(?:\\.data)?)\\." + key + "(?:\\.|(?![A-Za-z0-9_]))");
  }

  private String escapePointer(String segment) {
    return segment.replace("~", "~0").replace("/", "~1");
  }
}
