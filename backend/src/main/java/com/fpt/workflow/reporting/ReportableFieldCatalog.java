package com.fpt.workflow.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.form.domain.WorkflowForm;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Derives reporting projections only from explicitly opted-in schema fields. */
@Component
public class ReportableFieldCatalog {

  public List<FieldProjection> fields(WorkflowForm form) {
    JsonNode fields = form.getSchemaJson().path("fields");
    if (!fields.isArray()) {
      return List.of();
    }
    List<FieldProjection> result = new ArrayList<>();
    fields.forEach(
        field -> {
          boolean searchable = field.path("searchable").asBoolean(false);
          boolean filterable = field.path("filterable").asBoolean(false);
          boolean reportable = field.path("reportable").asBoolean(false);
          if (searchable || filterable || reportable) {
            String key = field.path("key").asText();
            String type = field.path("type").isTextual() ? field.path("type").asText() : "OBJECT";
            if (!key.isBlank()) {
              result.add(new FieldProjection(key, type, searchable, filterable, reportable));
            }
          }
        });
    return List.copyOf(result);
  }

  public record FieldProjection(
      String fieldKey,
      String canonicalType,
      boolean searchable,
      boolean filterable,
      boolean reportable) {}
}
