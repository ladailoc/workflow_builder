package com.fpt.workflow.shared.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.UUID;

/** Marker contract for canonical UUID-backed domain values. */
public interface UuidValue {

  @JsonValue
  UUID value();
}
