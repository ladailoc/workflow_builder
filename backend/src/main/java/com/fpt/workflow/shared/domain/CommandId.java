package com.fpt.workflow.shared.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fpt.workflow.shared.UuidGenerator;
import java.util.Objects;
import java.util.UUID;

public record CommandId(UUID value) implements UuidValue {

  public CommandId {
    Objects.requireNonNull(value, "value");
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static CommandId parse(String value) {
    return new CommandId(UUID.fromString(value));
  }

  public static CommandId generate(UuidGenerator uuidGenerator) {
    return new CommandId(Objects.requireNonNull(uuidGenerator, "uuidGenerator").generate());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
