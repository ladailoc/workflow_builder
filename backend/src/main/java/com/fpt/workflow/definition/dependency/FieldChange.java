package com.fpt.workflow.definition.dependency;

import com.fpt.workflow.shared.domain.value.TypeDescriptor;
import java.util.Objects;
import java.util.UUID;

public record FieldChange(
    UUID workflowVersionId,
    String fieldKey,
    FieldChangeKind kind,
    String replacementKey,
    TypeDescriptor replacementType) {

  public FieldChange {
    Objects.requireNonNull(workflowVersionId, "workflowVersionId");
    fieldKey = requireKey(fieldKey, "fieldKey");
    Objects.requireNonNull(kind, "kind");
    if (kind == FieldChangeKind.RENAME) {
      replacementKey = requireKey(replacementKey, "replacementKey");
    } else if (replacementKey != null) {
      throw new IllegalArgumentException("replacementKey is only valid for RENAME");
    }
    if (kind == FieldChangeKind.TYPE_CHANGE) {
      Objects.requireNonNull(replacementType, "replacementType");
    } else if (replacementType != null) {
      throw new IllegalArgumentException("replacementType is only valid for TYPE_CHANGE");
    }
  }

  public static FieldChange rename(UUID versionId, String fieldKey, String replacementKey) {
    return new FieldChange(versionId, fieldKey, FieldChangeKind.RENAME, replacementKey, null);
  }

  public static FieldChange delete(UUID versionId, String fieldKey) {
    return new FieldChange(versionId, fieldKey, FieldChangeKind.DELETE, null, null);
  }

  public static FieldChange typeChange(
      UUID versionId, String fieldKey, TypeDescriptor replacementType) {
    return new FieldChange(versionId, fieldKey, FieldChangeKind.TYPE_CHANGE, null, replacementType);
  }

  private static String requireKey(String value, String name) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException(name + " must be a canonical field key");
    }
    return value;
  }
}
