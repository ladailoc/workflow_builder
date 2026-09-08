package com.fpt.workflow.runtime.context;

import java.util.Objects;
import java.util.UUID;

public record SensitiveValueMetadata(String path, String sourceType, UUID sourceId) {

  public SensitiveValueMetadata {
    path = Objects.requireNonNull(path, "path");
    sourceType = Objects.requireNonNull(sourceType, "sourceType");
    sourceId = Objects.requireNonNull(sourceId, "sourceId");
  }
}
