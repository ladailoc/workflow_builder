package com.fpt.workflow.definition.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WorkflowGraphDtosTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void normalizesExplicitNullOptionalGraphSchemas() throws Exception {
    var node =
        objectMapper.readValue(
            """
            {
              "clientRef": "start",
              "nodeKey": "start",
              "nodeType": "START",
              "name": "Start",
              "description": null,
              "configSchemaVersion": 1,
              "configJson": {},
              "inputSchemaJson": null,
              "outputSchemaJson": null,
              "positionJson": {"x": 0, "y": 0}
            }
            """,
            WorkflowGraphDtos.GraphNode.class);

    assertThat(node.inputSchemaJson()).isNull();
    assertThat(node.outputSchemaJson()).isNull();
  }

  @Test
  void normalizesExplicitNullOptionalEdgeCondition() throws Exception {
    var edge =
        objectMapper.readValue(
            """
            {
              "clientRef": "start-to-end",
              "sourceClientRef": "start",
              "sourcePort": "DEFAULT",
              "targetClientRef": "end",
              "conditionJson": null,
              "priority": 0,
              "defaultTransition": true,
              "transitionType": "NORMAL",
              "label": null,
              "configJson": {}
            }
            """,
            WorkflowGraphDtos.GraphEdge.class);

    assertThat(edge.conditionJson()).isNull();
  }
}
