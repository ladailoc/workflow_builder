package com.fpt.workflow.definition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.TransitionType;
import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVersionRepository;
import com.fpt.workflow.definition.service.WorkflowSimulationService;
import com.fpt.workflow.runtime.repository.EventRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P2-08: dry-run simulation on a draft definition (§22.8). No Event row, no connector call, no
 * business side effect; structured result with routes, participants, MI planning, sub-workflow
 * mapping, failure surface.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.springframework.transaction.annotation.Transactional
class WorkflowSimulationIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_simulation_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private WorkflowSimulationService simulationService;
  @Autowired private WorkflowDefinitionRepository definitionRepository;
  @Autowired private WorkflowVersionRepository versionRepository;
  @Autowired private NodeDefinitionRepository nodeRepository;
  @Autowired private EdgeDefinitionRepository edgeRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @WithMockUser(roles = {"WORKFLOW_OWNER", "ADMIN"})
  void simulate_classApprovalWorkflow_returnsDeterministicDryRunWithoutPersistingEvent() {
    UUID definitionId = UUID.randomUUID();
    definitionRepository.save(
        WorkflowDefinition.create(
            definitionId,
            "SIM_" + UUID.randomUUID().toString().replace("-", ""),
            "Simulation workflow",
            "P2-08",
            UUID.fromString("10000000-0000-4000-8000-000000001501"),
            UUID.fromString("10000000-0000-4000-8000-000000001501"),
            Instant.EPOCH));

    UUID versionId = UUID.randomUUID();
    versionRepository.save(
        WorkflowVersion.createDraft(versionId, definitionId, 1, null, null,
            UUID.fromString("10000000-0000-4000-8000-000000001501"), Instant.EPOCH));

    NodeDefinition start = node(versionId, "start", "START", empty());
    NodeDefinition review = node(versionId, "mi_review", "REVIEW", reviewCfg());
    NodeDefinition approval = node(versionId, "approval", "APPROVAL", approvalCfg());
    NodeDefinition systemAction =
        node(versionId, "notify", "SYSTEM_ACTION", systemActionCfg());
    NodeDefinition subwf = node(versionId, "sub", "SUB_WORKFLOW", subwfCfg());
    NodeDefinition end = node(versionId, "end", "END", endCfg());
    nodeRepository.saveAll(java.util.List.of(start, review, approval, systemAction, subwf, end));

    edgeRepository.save(edge(versionId, start, "STARTED", review, 0));
    edgeRepository.save(edge(versionId, review, "SUBMITTED", approval, 0));
    edgeRepository.save(edge(versionId, review, "RETURNED", end, 1));
    edgeRepository.save(edge(versionId, approval, "APPROVED", systemAction, 0));
    edgeRepository.save(edge(versionId, approval, "REJECTED", end, 0));
    edgeRepository.save(edge(versionId, systemAction, "SUCCESS", subwf, 0));
    edgeRepository.save(edge(versionId, subwf, "COMPLETED", end, 0));

    long eventsBefore = eventRepository.count();

    com.fasterxml.jackson.databind.node.ObjectNode ticketData = objectMapper.createObjectNode();
    com.fasterxml.jackson.databind.node.ArrayNode peers = ticketData.putArray("peers");
    peers.add(UUID.randomUUID().toString()).add(UUID.randomUUID().toString());

    WorkflowSimulationService.SimulationResult result =
        simulationService.simulate(
            versionId,
            new WorkflowSimulationService.SampleContext(ticketData, objectMapper.createArrayNode()));

    // Deterministic: same input simulation twice yields identical transition order.
    WorkflowSimulationService.SimulationResult again =
        simulationService.simulate(
            versionId,
            new WorkflowSimulationService.SampleContext(ticketData, objectMapper.createArrayNode()));
    assertThat(result.transitions()).isEqualTo(again.transitions());

    assertThat(result.transitions())
        .extracting(WorkflowSimulationService.SimulatedTransition::sourceNodeKey)
        .contains("start", "mi_review", "approval", "notify", "sub");
    assertThat(result.participants()).isNotEmpty();
    assertThat(result.multiInstancePlans())
        .extracting(WorkflowSimulationService.SimulatedMultiInstancePlan::plannedCount)
        .contains(2);
    assertThat(result.subWorkflows())
        .extracting(WorkflowSimulationService.SimulatedSubWorkflow::disposition)
        .containsOnly("WOULD_CREATE_CHILD_EVENT");

    // No side effects: no real Event row, no connector invocation possible in dry-run.
    assertThat(eventRepository.count()).isEqualTo(eventsBefore);
  }

  private com.fasterxml.jackson.databind.JsonNode empty() {
    return objectMapper.createObjectNode();
  }

  private com.fasterxml.jackson.databind.JsonNode reviewCfg() {
    var cfg = objectMapper.createObjectNode();
    var participant = cfg.putObject("participant");
    participant.put("type", "FIXED_USER");
    participant.put("userId", UUID.randomUUID().toString());
    cfg.putArray("allowedActions").add("SUBMIT").add("RETURN");
    var mi = cfg.putObject("multiInstance");
    mi.put("collection", "${ticket.data.peers}");
    mi.put("itemVariable", "peer");
    mi.put("executionMode", "PARALLEL");
    mi.put("completionPolicy", "ALL");
    mi.put("remainingItemPolicy", "CANCEL_REMAINING");
    return cfg;
  }

  private com.fasterxml.jackson.databind.JsonNode approvalCfg() {
    var cfg = objectMapper.createObjectNode();
    var participant = cfg.putObject("participant");
    participant.put("type", "FIXED_USER");
    participant.put("userId", UUID.randomUUID().toString());
    cfg.putArray("allowedActions").add("APPROVE").add("REJECT");
    return cfg;
  }

  private com.fasterxml.jackson.databind.JsonNode systemActionCfg() {
    var cfg = objectMapper.createObjectNode();
    cfg.put("connectorKey", "SIM_CONN");
    cfg.put("actionKey", "SIM_ACTION");
    cfg.put("actionVersion", 1);
    cfg.put("credentialRef", "vault://sim/conn");
    return cfg;
  }

  private com.fasterxml.jackson.databind.JsonNode subwfCfg() {
    var cfg = objectMapper.createObjectNode();
    cfg.put("childWorkflowDefinitionKey", "CHILD_SIM");
    cfg.put("executionMode", "WAIT_FOR_COMPLETION");
    cfg.put("cancellationPolicy", "PROPAGATE");
    return cfg;
  }

  private com.fasterxml.jackson.databind.JsonNode endCfg() {
    var cfg = objectMapper.createObjectNode();
    cfg.put("outcome", "COMPLETED");
    return cfg;
  }

  private NodeDefinition node(
      UUID versionId, String key, String type, com.fasterxml.jackson.databind.JsonNode config) {
    return NodeDefinition.create(
        UUID.randomUUID(), versionId, key, type, key, null, 1, config, null, null, objectMapper.createObjectNode());
  }

  private com.fpt.workflow.definition.domain.EdgeDefinition edge(
      UUID versionId, NodeDefinition source, String port, NodeDefinition target, int priority) {
    return com.fpt.workflow.definition.domain.EdgeDefinition.create(
        UUID.randomUUID(), versionId, source.getId(), port, target.getId(), null, priority, false,
        TransitionType.NORMAL, null, objectMapper.createObjectNode());
  }
}
