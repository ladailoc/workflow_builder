package com.fpt.workflow.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.nodetype.*;
import com.fpt.workflow.resolver.expression.SafeExpressionEngine;
import com.fpt.workflow.runtime.join.domain.JoinPolicy;
import com.fpt.workflow.runtime.join.domain.JoinState;
import com.fpt.workflow.runtime.subworkflow.domain.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PurchaseApprovalGoldenE2EGateTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private JsonNode workflow;
  private Map<String, JsonNode> nodes;
  private NodeTypeRegistry registry;

  @BeforeEach
  void setUp() throws Exception {
    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    Path definition = root.resolve("../docs/examples/purchase-approval.workflow.json").normalize();
    if (!Files.exists(definition)) {
      definition = root.resolve("docs/examples/purchase-approval.workflow.json").normalize();
    }
    workflow = mapper.readTree(definition.toFile());
    nodes =
        iterable(workflow.path("nodes")).stream()
            .collect(Collectors.toMap(n -> n.path("key").asText(), Function.identity()));
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
  void canonicalPurchaseApprovalIsEntirelyGenericAndStrictlyConfigured() {
    assertThat(workflow.path("sampleTicket").path("amount").asLong()).isEqualTo(150_000_000L);
    assertThat(nodes.keySet())
        .containsExactlyInAnyOrder(
            "start",
            "managerApproval",
            "vendorVerification",
            "riskCondition",
            "directorPreReview",
            "parallelReviews",
            "financeReview",
            "legalReview",
            "reviewsJoin",
            "createPurchaseOrder",
            "notifyCreator",
            "end");

    nodes
        .values()
        .forEach(
            node -> {
              NodeType type = NodeType.valueOf(node.path("type").asText());
              assertThat(
                      registry
                          .require(type)
                          .validate(node.path("key").asText(), 1, node.path("config")))
                  .as(node.path("key").asText())
                  .isEmpty();
              assertThat(node.path("config").has("target")).isFalse();
              assertThat(node.path("config").has("targetNode")).isFalse();
              assertThat(node.path("config").has("destination")).isFalse();
            });

    Set<String> edgeKeys =
        iterable(workflow.path("edges")).stream()
            .map(
                e ->
                    e.path("source").asText()
                        + ":"
                        + e.path("port").asText()
                        + ":"
                        + e.path("target").asText())
            .collect(Collectors.toSet());
    assertThat(edgeKeys)
        .contains(
            "managerApproval:APPROVED:vendorVerification",
            "vendorVerification:COMPLETED:riskCondition",
            "parallelReviews:SPLIT:financeReview",
            "parallelReviews:SPLIT:legalReview",
            "financeReview:SUBMITTED:reviewsJoin",
            "legalReview:SUBMITTED:reviewsJoin",
            "createPurchaseOrder:SUCCESS:notifyCreator",
            "notifyCreator:QUEUED:end");
  }

  @Test
  void highAndLowRiskRoutingUsesSafeTypedConditionSnapshot() {
    JsonNode config = nodes.get("riskCondition").path("config");
    ConditionNodeHandler handler = new ConditionNodeHandler(mapper, new SafeExpressionEngine());

    assertThat(outcome(handler, config, "HIGH")).isEqualTo("TRUE");
    assertThat(outcome(handler, config, "LOW")).isEqualTo("FALSE");
  }

  @Test
  void childVersionIsPinnedAndParallelJoinAllRoutesExactlyOnce() {
    UUID childV4 = UUID.randomUUID();
    SubWorkflowExecution child =
        SubWorkflowExecution.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            childV4,
            SubWorkflowExecutionMode.WAIT_FOR_COMPLETION,
            SubWorkflowCancellationPolicy.PROPAGATE,
            "{}",
            Instant.parse("2026-09-08T00:00:00Z"));
    UUID subsequentlyPublishedV5 = UUID.randomUUID();
    assertThat(subsequentlyPublishedV5).isNotEqualTo(childV4);
    assertThat(child.getChildWorkflowVersionId()).isEqualTo(childV4);

    NodeExecutionResult split =
        new ParallelSplitNodeHandler()
            .execute(
                new NodeHandlerContext(UUID.randomUUID(), "parallelReviews", object(), object()));
    assertThat(((NodeExecutionResult.Complete) split).outcomePort()).isEqualTo("SPLIT");

    JoinState join =
        JoinState.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            JoinPolicy.AND,
            2,
            null,
            Instant.parse("2026-09-08T00:00:00Z"));
    assertThat(join.recordArrival(Instant.parse("2026-09-08T00:00:01Z"))).isFalse();
    assertThat(join.recordArrival(Instant.parse("2026-09-08T00:00:02Z"))).isTrue();
    assertThat(join.markRoutedDownstream()).isTrue();
    assertThat(join.markRoutedDownstream()).isFalse();
  }

  @Test
  void erpContractIsVersionPinnedAndNotificationIsDurablyDeduplicated() {
    JsonNode action = nodes.get("createPurchaseOrder").path("config");
    assertThat(action.path("connectorKey").asText()).isEqualTo("ERP");
    assertThat(action.path("actionKey").asText()).isEqualTo("CREATE_PURCHASE_ORDER");
    assertThat(action.path("actionVersion").asInt()).isEqualTo(2);
    assertThat(workflow.path("externalContracts").path("erpIdempotencyRequired").asBoolean())
        .isTrue();
    assertThat(workflow.path("externalContracts").path("uncertainNonIdempotentOutcome").asText())
        .isEqualTo("MANUAL_RECONCILIATION");

    CapturingRuntime runtime = new CapturingRuntime();
    UUID nodeExecutionId = UUID.randomUUID();
    NodeExecutionResult result =
        new NotificationNodeHandler()
            .execute(
                new NodeHandlerContext(
                    nodeExecutionId,
                    "notifyCreator",
                    object().put("orderId", "PO-2026-1"),
                    nodes.get("notifyCreator").path("config"),
                    runtime));
    assertThat(((NodeExecutionResult.Complete) result).outcomePort()).isEqualTo("QUEUED");
    assertThat(runtime.nodeExecutionId).isEqualTo(nodeExecutionId);
    assertThat(runtime.dedupKey).isEqualTo("notification-node:" + nodeExecutionId);
  }

  private String outcome(ConditionNodeHandler handler, JsonNode config, String risk) {
    ObjectNode input = object();
    input.putObject("variables").put("riskLevel", risk);
    NodeExecutionResult result =
        handler.execute(new NodeHandlerContext(UUID.randomUUID(), "riskCondition", input, config));
    assertThat(result).isInstanceOf(NodeExecutionResult.Complete.class);
    return ((NodeExecutionResult.Complete) result).outcomePort();
  }

  private static List<JsonNode> iterable(JsonNode array) {
    List<JsonNode> result = new ArrayList<>();
    array.forEach(result::add);
    return result;
  }

  private static ObjectNode object() {
    return new ObjectMapper().createObjectNode();
  }

  private static final class CapturingRuntime implements NodeRuntimeServices {
    private UUID nodeExecutionId;
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
    public void scheduleNotification(
        UUID nodeExecutionId, JsonNode input, JsonNode configuration, String dedupKey) {
      this.nodeExecutionId = nodeExecutionId;
      this.dedupKey = dedupKey;
    }
  }
}
