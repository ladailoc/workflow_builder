package com.fpt.workflow.form.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record FieldOptions(
    FieldOptionSource source, List<JsonNode> staticValues, String dataSourceKey) {

  private static final Pattern DATA_SOURCE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,127}");

  public FieldOptions {
    source = Objects.requireNonNull(source, "source");
    staticValues =
        List.copyOf(Objects.requireNonNull(staticValues, "staticValues")).stream()
            .map(value -> (JsonNode) value.deepCopy())
            .toList();
    if (dataSourceKey != null && !DATA_SOURCE_KEY.matcher(dataSourceKey).matches()) {
      throw new IllegalArgumentException("dataSourceKey must be a registered key, not a URL");
    }
  }

  public static FieldOptions staticValues(List<JsonNode> values) {
    return new FieldOptions(FieldOptionSource.STATIC, values, null);
  }

  public static FieldOptions dataSource(String registeredKey) {
    return new FieldOptions(FieldOptionSource.DATA_SOURCE, List.of(), registeredKey);
  }
}
