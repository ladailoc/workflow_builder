package com.fpt.workflow.runtime.activation;

import com.fpt.workflow.nodetype.NodeRuntimeServices;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class DefaultNodeRuntimeServices implements NodeRuntimeServices {

  private final PlatformClock clock;
  private final UuidGenerator uuidGenerator;

  public DefaultNodeRuntimeServices(PlatformClock clock, UuidGenerator uuidGenerator) {
    this.clock = clock;
    this.uuidGenerator = uuidGenerator;
  }

  @Override
  public Instant now() {
    return clock.now();
  }

  @Override
  public UUID newId() {
    return uuidGenerator.generate();
  }
}
