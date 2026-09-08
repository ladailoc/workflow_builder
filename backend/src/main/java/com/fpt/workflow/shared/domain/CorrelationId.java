package com.fpt.workflow.shared.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fpt.workflow.shared.UuidGenerator;
import java.util.Objects;
import java.util.UUID;

public record CorrelationId(UUID value) implements UuidValue {

  public CorrelationId {
    Objects.requireNonNull(value, "value");
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static CorrelationId parse(String value) {
    return new CorrelationId(UUID.fromString(value));
  }

  public static CorrelationId generate(UuidGenerator uuidGenerator) {
    return new CorrelationId(Objects.requireNonNull(uuidGenerator, "uuidGenerator").generate());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
