package com.fpt.workflow.shared.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fpt.workflow.shared.UuidGenerator;
import java.util.Objects;
import java.util.UUID;

public record AggregateId(UUID value) implements UuidValue {

  public AggregateId {
    Objects.requireNonNull(value, "value");
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static AggregateId parse(String value) {
    return new AggregateId(UUID.fromString(value));
  }

  public static AggregateId generate(UuidGenerator uuidGenerator) {
    return new AggregateId(Objects.requireNonNull(uuidGenerator, "uuidGenerator").generate());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
