package com.fpt.workflow.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.fpt.workflow.definition.domain.*;
import com.fpt.workflow.nodetype.*;
import com.fpt.workflow.organization.service.OrganizationHierarchyService;
import com.fpt.workflow.resolver.domain.ParticipantSnapshot;
import com.fpt.workflow.resolver.repository.ParticipantSnapshotRepository;
import com.fpt.workflow.runtime.domain.*;
import com.fpt.workflow.runtime.multiinstance.domain.NodeItemExecution;
import com.fpt.workflow.runtime.multiinstance.repository.NodeItemExecutionRepository;
import com.fpt.workflow.runtime.rework.*;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.lifecycle.NodeExecutionStatus;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import com.fpt.workflow.task.service.HumanTaskParticipantActivationHook;
import com.fpt.workflow.task.service.TaskSlaActivationPort;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EmployeeEvaluationE2EGateTest {
  @Test
  void fiveEmployeeEvaluationReworkAndActivationTimeManagerSnapshot() {
    UUID creator = UUID.randomUUID(),
        managerBefore = UUID.randomUUID(),
        managerAtActivation = UUID.randomUUID(),
        managerAfter = UUID.randomUUID(),
        hr = UUID.randomUUID();
    List<UUID> employees =
        java.util.stream.IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList();
    var taskRepo = mock(TaskExecutionRepository.class);
    var snapshots = mock(ParticipantSnapshotRepository.class);
    var registry = mock(NodeTypeRegistry.class);
    var hierarchy = mock(OrganizationHierarchyService.class);
    var items = mock(NodeItemExecutionRepository.class);
    var sla = mock(TaskSlaActivationPort.class);
    NodeTypeManifest manifest = mock(NodeTypeManifest.class);
    when(manifest.supportedCapabilities())
        .thenReturn(Set.of(NodeCapability.HUMAN_TASK, NodeCapability.PARTICIPANT));
    when(sla.plan(any(), any())).thenReturn(Optional.empty());
    when(registry.require(any())).thenReturn(manifest);
    List<TaskExecution> createdTasks = new ArrayList<>();
    List<ParticipantSnapshot> createdSnapshots = new ArrayList<>();
    when(taskRepo.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              createdTasks.add(i.getArgument(0));
              return i.getArgument(0);
            });
    when(snapshots.save(any()))
        .thenAnswer(
            i -> {
              createdSnapshots.add(i.getArgument(0));
              return i.getArgument(0);
            });
    Instant now = Instant.parse("2026-09-08T07:00:00Z");
    PlatformClock clock = () -> now;
    UuidGenerator ids = UUID::randomUUID;
    HumanTaskParticipantActivationHook hook =
        new HumanTaskParticipantActivationHook(
            taskRepo, snapshots, registry, hierarchy, ids, clock, new ObjectMapper(), sla, items);
    UUID eventId = UUID.randomUUID(), versionId = UUID.randomUUID(), revisionId = UUID.randomUUID();
    Event event =
        Event.createRoot(
            eventId,
            UUID.randomUUID(),
            versionId,
            revisionId,
            null,
            null,
            "TEST",
            "employee-eval",
            object(),
            creator,
            now.minusSeconds(1));
    event.markRunning();

    // SELF_EVALUATION: one item-correlated task, assigned to each employee.
    NodeExecution selfExec = execution(eventId, UUID.randomUUID(), revisionId, 0, null, now);
    when(items.findAllByParentNodeExecutionIdOrderByItemIndexAsc(selfExec.getId()))
        .thenReturn(itemExecutions(eventId, selfExec.getId(), employees, now));
    hook.onActivation(
        event,
        node(
            versionId,
            selfExec.getNodeDefinitionId(),
            "self-evaluation",
            "REVIEW",
            "ITEM_USER",
            true,
            null),
        selfExec,
        null);
    assertThat(createdTasks).hasSize(5);
    assertThat(createdTasks)
        .extracting(TaskExecution::getAssigneeId)
        .containsExactlyElementsOf(employees);
    assertThat(createdTasks).allMatch(t -> t.getItemExecutionId() != null);

    // MANAGER_REVIEW: same five subjects, five separate tasks, all assigned to creator A.
    createdTasks.clear();
    createdSnapshots.clear();
    NodeExecution managerExec = execution(eventId, UUID.randomUUID(), revisionId, 0, null, now);
    when(items.findAllByParentNodeExecutionIdOrderByItemIndexAsc(managerExec.getId()))
        .thenReturn(itemExecutions(eventId, managerExec.getId(), employees, now));
    hook.onActivation(
        event,
        node(
            versionId,
            managerExec.getNodeDefinitionId(),
            "manager-review",
            "REVIEW",
            "CREATOR",
            true,
            null),
        managerExec,
        null);
    assertThat(createdTasks).hasSize(5).allMatch(t -> creator.equals(t.getAssigneeId()));
    assertThat(createdSnapshots)
        .extracting(ParticipantSnapshot::getSubjectRefId)
        .containsExactlyElementsOf(employees);

    // Manager changes before activation: resolution sees the new manager. Later changes do not
    // mutate the snapshot/task.
    when(hierarchy.resolveManagerAtDepth(eq(creator), eq(1), any())).thenReturn(managerBefore);
    when(hierarchy.resolveManagerAtDepth(eq(creator), eq(1), any()))
        .thenReturn(managerAtActivation);
    createdTasks.clear();
    createdSnapshots.clear();
    NodeExecution higherExec = execution(eventId, UUID.randomUUID(), revisionId, 0, null, now);
    hook.onActivation(
        event,
        node(
            versionId,
            higherExec.getNodeDefinitionId(),
            "higher-manager",
            "APPROVAL",
            "MANAGER_OF",
            false,
            null),
        higherExec,
        null);
    TaskExecution higherTask = createdTasks.getFirst();
    assertThat(higherTask.getAssigneeId()).isEqualTo(managerAtActivation);
    when(hierarchy.resolveManagerAtDepth(eq(creator), eq(1), any())).thenReturn(managerAfter);
    assertThat(higherTask.getAssigneeId()).isEqualTo(managerAtActivation);
    assertThat(createdSnapshots.getFirst().getResolvedUserId()).isEqualTo(managerAtActivation);

    // Reject uses an explicit bounded REWORK edge and creates a distinct manager-review occurrence.
    NodeExecution completedHigher =
        execution(eventId, higherExec.getNodeDefinitionId(), revisionId, 0, null, now);
    completedHigher.complete("REJECTED", object(), now);
    ObjectNode policy = object();
    policy.set(
        "reworkPolicy",
        object()
            .put("maxIterations", 3)
            .put("onExhausted", "FAIL_EVENT")
            .put("scope", "WHOLE_NODE"));
    EdgeDefinition rework =
        EdgeDefinition.create(
            UUID.randomUUID(),
            versionId,
            higherExec.getNodeDefinitionId(),
            "REJECTED",
            managerExec.getNodeDefinitionId(),
            null,
            0,
            true,
            TransitionType.REWORK,
            "return",
            policy);
    ReworkPlan plan = new ReworkRuntimePlanner().plan(eventId, rework, completedHigher);
    NodeExecution managerRework =
        execution(
            eventId,
            managerExec.getNodeDefinitionId(),
            revisionId,
            plan.iteration(),
            plan.itemToken(),
            now);
    assertThat(managerRework.getId()).isNotEqualTo(managerExec.getId());
    assertThat(completedHigher.getStatus()).isEqualTo(NodeExecutionStatus.COMPLETED);
    assertThat(plan.iteration()).isEqualTo(1);

    // Approve proceeds to HR review and END through generic participant/handler contracts.
    createdTasks.clear();
    NodeExecution hrExec = execution(eventId, UUID.randomUUID(), revisionId, 1, null, now);
    hook.onActivation(
        event,
        node(
            versionId,
            hrExec.getNodeDefinitionId(),
            "hr-review",
            "REVIEW",
            "FIXED_USER",
            false,
            hr),
        hrExec,
        null);
    assertThat(createdTasks.getFirst().getAssigneeId()).isEqualTo(hr);
    NodeExecutionResult end =
        new EndNodeHandler()
            .execute(
                new NodeHandlerContext(
                    UUID.randomUUID(), "end", object(), object().put("outcome", "APPROVED")));
    assertThat(end).isInstanceOf(NodeExecutionResult.Complete.class);
    assertThat(((NodeExecutionResult.Complete) end).outcomePort()).isEqualTo("COMPLETED");
  }

  private NodeDefinition node(
      UUID version, UUID id, String key, String type, String resolver, boolean multi, UUID fixed) {
    ObjectNode cfg = object();
    ObjectNode p = object().put("type", resolver);
    if (fixed != null) p.put("userId", fixed.toString());
    cfg.set("participant", p);
    cfg.set("allowedActions", JsonNodeFactory.instance.arrayNode().add("APPROVE").add("REJECT"));
    if (multi)
      cfg.set("multiInstance", object().put("collectionPath", "ticket.data.evaluationTargets"));
    return NodeDefinition.create(id, version, key, type, key, null, 1, cfg, null, null, object());
  }

  private NodeExecution execution(
      UUID event, UUID node, UUID revision, int iteration, String item, Instant now) {
    NodeExecution e =
        NodeExecution.create(
            UUID.randomUUID(),
            event,
            node,
            "activation:" + UUID.randomUUID(),
            UUID.randomUUID(),
            iteration,
            "root",
            item,
            null,
            null,
            object(),
            revision,
            now);
    e.markReady();
    e.start(now);
    return e;
  }

  private List<NodeItemExecution> itemExecutions(
      UUID event, UUID parent, List<UUID> users, Instant now) {
    UUID state = UUID.randomUUID();
    List<NodeItemExecution> result = new ArrayList<>();
    for (int i = 0; i < users.size(); i++) {
      NodeItemExecution item =
          NodeItemExecution.create(
              UUID.randomUUID(),
              state,
              event,
              parent,
              i,
              "item-" + i,
              JsonNodeFactory.instance.textNode(users.get(i).toString()),
              now);
      item.markRunning(now);
      result.add(item);
    }
    return result;
  }

  private static ObjectNode object() {
    return JsonNodeFactory.instance.objectNode();
  }
}
