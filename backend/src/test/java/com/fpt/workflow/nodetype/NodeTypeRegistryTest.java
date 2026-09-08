package com.fpt.workflow.nodetype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeTypeRegistryTest {

  private NodeTypeRegistry registry;

  @BeforeEach
  void setUp() {
    CoreNodeTypeConfiguration configuration = new CoreNodeTypeConfiguration();
    registry =
        new NodeTypeRegistry(
            List.of(
                configuration.startNodeTypeProvider(),
                configuration.endNodeTypeProvider(),
                configuration.approvalNodeTypeProvider(),
                configuration.reviewNodeTypeProvider(),
                configuration.conditionNodeTypeProvider(),
                configuration.parallelSplitNodeTypeProvider(),
                configuration.joinNodeTypeProvider(),
                configuration.systemActionNodeTypeProvider(),
                configuration.subWorkflowNodeTypeProvider(),
                configuration.notificationNodeTypeProvider()));
  }

  @Test
  void registersOnlyInitialP0ManifestsWithStrictSchemas() {
    assertThat(registry.manifests().keySet()).containsExactlyInAnyOrder(NodeType.values());
    assertThat(registry.manifests().values())
        .allSatisfy(
            manifest -> {
              assertThat(manifest.currentConfigSchemaVersion()).isPositive();
              assertThat(manifest.configSchema().additionalProperties()).isFalse();
              assertThat(manifest.uiSchema().isObject()).isTrue();
              assertThat(manifest.validator()).isNotNull();
              assertThat(manifest.handler().supports()).isEqualTo(manifest.nodeType());
            });
  }

  @Test
  void rejectsDuplicateProviderRegistration() {
    NodeTypeProvider start = () -> registry.require(NodeType.START);

    assertThatThrownBy(() -> new NodeTypeRegistry(List.of(start, start)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate node type registration: START");
  }

  @Test
  void unknownExecutableConfigPropertyIsBlocking() {
    var config = JsonNodeFactory.instance.objectNode();
    config.putObject("expression").put("operator", "EQ");
    config.put("targetNode", "must-never-live-in-config");

    List<NodeValidationIssue> issues =
        registry.require(NodeType.CONDITION).validate("routeRequest", 1, config);

    assertThat(issues)
        .anySatisfy(
            issue -> {
              assertThat(issue.code()).isEqualTo("SCHEMA.UNKNOWN_PROPERTY");
              assertThat(issue.fieldPath()).isEqualTo("$.targetNode");
              assertThat(issue.blocking()).isTrue();
            });
  }

  @Test
  void rejectsUnsupportedConfigSchemaVersionBeforeSemanticValidation() {
    List<NodeValidationIssue> issues =
        registry
            .require(NodeType.START)
            .validate("start", 2, JsonNodeFactory.instance.objectNode());

    assertThat(issues)
        .singleElement()
        .extracting(NodeValidationIssue::code)
        .isEqualTo("NODE.CONFIG_SCHEMA_VERSION_UNSUPPORTED");
  }

  @Test
  void handlerContractHasNoRoutingDestinationAndSupportsAllResultVariants() {
    var factory = JsonNodeFactory.instance;
    List<NodeExecutionResult> results = new ArrayList<>();
    results.add(NodeExecutionResult.complete(factory.objectNode(), "APPROVED"));
    results.add(
        NodeExecutionResult.waitFor(
            new WaitDescriptor("HUMAN_TASK", "task-1", factory.objectNode())));
    results.add(
        NodeExecutionResult.fail(
            new NodeExecutionError("NODE.FAILURE", "failed", factory.objectNode())));

    assertThat(results)
        .extracting(result -> result.getClass().getSimpleName())
        .containsExactly("Complete", "Wait", "Fail");
    assertThat(NodeHandlerContext.class.getRecordComponents())
        .extracting(component -> component.getName())
        .doesNotContain("target", "targetNode", "nextNode", "routingService");
  }

  @Test
  void manifestRejectsNonStrictConfigSchema() {
    var permissive =
        new com.fpt.workflow.shared.domain.value.CanonicalSchema(
            java.util.Map.of(), Set.of(), true);
    NodeHandler handler = new ContractOnlyNodeHandler(NodeType.START);

    assertThatThrownBy(
            () ->
                new NodeTypeManifest(
                    NodeType.START,
                    1,
                    Set.of(),
                    permissive,
                    permissive,
                    Set.of("STARTED"),
                    permissive,
                    JsonNodeFactory.instance.objectNode(),
                    context -> List.of(),
                    handler))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be strict");
  }
}
