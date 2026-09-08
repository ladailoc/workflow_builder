package com.fpt.workflow.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.nodetype.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SystemActionDurableSchedulingTest {
  @Test
  void handlerPersistsOnlyDurableIntentAndWaitsWithoutSelectingDestination() {
    UUID executionId = UUID.randomUUID();
    CapturingRuntime runtime = new CapturingRuntime();
    NodeExecutionResult result =
        new SystemActionNodeHandler()
            .execute(
                new NodeHandlerContext(
                    executionId,
                    "erp-create-po",
                    JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(),
                    runtime));

    assertThat(result).isInstanceOf(NodeExecutionResult.Wait.class);
    assertThat(((NodeExecutionResult.Wait) result).descriptor().waitType())
        .isEqualTo("RETRY_BACKOFF");
    assertThat(runtime.jobType).isEqualTo("SYSTEM_ACTION_EXECUTE");
    assertThat(runtime.aggregateId).isEqualTo(executionId);
    assertThat(runtime.dedupKey).isEqualTo("system-action:" + executionId);
    assertThat(runtime.payload.path("nodeExecutionId").asText()).isEqualTo(executionId.toString());
    assertThat(runtime.payload.toString()).doesNotContain("targetNode");
  }

  private static final class CapturingRuntime implements NodeRuntimeServices {
    private String jobType;
    private UUID aggregateId;
    private JsonNode payload;
    private String dedupKey;

    @Override
    public Instant now() {
      return Instant.parse("2026-09-08T00:00:00Z");
    }

    @Override
    public UUID newId() {
      return UUID.randomUUID();
    }

    @Override
    public void scheduleDurableJob(
        String jobType,
        UUID aggregateId,
        JsonNode payload,
        int maxAttempts,
        Instant nextRunAt,
        String dedupKey) {
      this.jobType = jobType;
      this.aggregateId = aggregateId;
      this.payload = payload;
      this.dedupKey = dedupKey;
    }
  }
}
