package com.fpt.workflow.definition.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowGraphDomainTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void rejectsRoutingDestinationDuplicatedInsideNodeConfig() {
    var config =
        objectMapper
            .createObjectNode()
            .set(
                "routing",
                objectMapper.createObjectNode().put("targetNodeId", UUID.randomUUID().toString()));

    assertThatThrownBy(
            () ->
                NodeDefinition.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "managerApproval",
                    "APPROVAL",
                    "Manager approval",
                    null,
                    1,
                    config,
                    null,
                    null,
                    objectMapper.createObjectNode()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not duplicate");
  }

  @Test
  void rejectsNegativeEdgePriority() {
    assertThatThrownBy(
            () ->
                EdgeDefinition.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "SUCCESS",
                    UUID.randomUUID(),
                    null,
                    -1,
                    false,
                    TransitionType.NORMAL,
                    null,
                    objectMapper.createObjectNode()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priority");
  }
}
