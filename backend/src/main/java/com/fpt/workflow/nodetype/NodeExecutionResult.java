package com.fpt.workflow.nodetype;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Closed handler result contract. It conveys an outcome port, never a target node. */
public sealed interface NodeExecutionResult
    permits NodeExecutionResult.Complete, NodeExecutionResult.Wait, NodeExecutionResult.Fail {

  static Complete complete(JsonNode output, String outcomePort) {
    return new Complete(output, outcomePort);
  }

  static Wait waitFor(WaitDescriptor descriptor) {
    return new Wait(descriptor);
  }

  static Fail fail(NodeExecutionError error) {
    return new Fail(error);
  }

  record Complete(JsonNode output, String outcomePort) implements NodeExecutionResult {
    public Complete {
      output = Objects.requireNonNull(output, "output").deepCopy();
      if (outcomePort == null || outcomePort.isBlank()) {
        throw new IllegalArgumentException("outcomePort must not be blank");
      }
    }
  }

  record Wait(WaitDescriptor descriptor) implements NodeExecutionResult {
    public Wait {
      descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }
  }

  record Fail(NodeExecutionError error) implements NodeExecutionResult {
    public Fail {
      error = Objects.requireNonNull(error, "error");
    }
  }
}
