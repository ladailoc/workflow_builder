package com.fpt.workflow.testing;

import com.fpt.workflow.shared.UuidGenerator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class FixedUuidGenerator implements UuidGenerator {

  private final List<UUID> values;
  private final AtomicInteger index = new AtomicInteger();

  public FixedUuidGenerator(UUID... values) {
    this.values = List.of(values);
  }

  @Override
  public UUID generate() {
    int currentIndex = index.getAndIncrement();
    if (currentIndex >= values.size()) {
      throw new IllegalStateException("No fixed UUID remains for this test");
    }
    return values.get(currentIndex);
  }
}
