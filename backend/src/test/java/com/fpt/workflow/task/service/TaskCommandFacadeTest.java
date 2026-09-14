package com.fpt.workflow.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.operations.command.CommandAction;
import com.fpt.workflow.operations.command.CommandExecutionResult;
import com.fpt.workflow.operations.command.CommandExecutor;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.domain.ExpectedVersion;
import com.fpt.workflow.shared.domain.lifecycle.TaskStatus;
import com.fpt.workflow.task.domain.TaskExecution;
import com.fpt.workflow.task.dto.TaskDtos;
import com.fpt.workflow.task.repository.TaskExecutionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskCommandFacadeTest {

  private final CommandExecutor commandExecutor = mock(CommandExecutor.class);
  private final TaskQueryService taskQueryService = mock(TaskQueryService.class);
  private final TaskExecutionRepository taskRepository = mock(TaskExecutionRepository.class);
  private final TaskRevisionRequestPort revisionRequestService = mock(TaskRevisionRequestPort.class);
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private TaskCommandFacade facade;
  private TaskExecution task;
  private TaskDtos.TaskItemView taskView;

  @BeforeEach
  void setUp() {
    UUID taskId = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-12T06:00:00Z");
    task =
        TaskExecution.create(
            taskId,
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            "Review",
            null,
            null,
            JsonNodeFactory.instance.objectNode(),
            50,
            null,
            now);
    taskView =
        new TaskDtos.TaskItemView(
            taskId,
            task.getNodeExecutionId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Review",
            null,
            TaskStatus.READY,
            null,
            50,
            task.getAssigneeId(),
            null,
            now,
            null,
            0,
            null,
            JsonNodeFactory.instance.objectNode());

    when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    when(taskQueryService.toView(task)).thenReturn(taskView);
    when(commandExecutor.execute(any(), any()))
        .thenAnswer(
            invocation -> {
              CommandAction action = invocation.getArgument(1);
              var completion = action.execute();
              return new CommandExecutionResult(
                  UUID.randomUUID(),
                  completion.resultJson(),
                  completion.resultMetadataJson(),
                  false);
            });

    facade =
        new TaskCommandFacade(
            commandExecutor,
            taskQueryService,
            taskRepository,
            objectMapper,
            new CanonicalDefinitionJson(objectMapper),
            revisionRequestService);
  }

  @Test
  void requestRevisionForwardsTypedRequestedFieldsThroughTheCommandBoundary() {
    UUID targetNodeId = UUID.randomUUID();
    ArrayNode requestedFields = JsonNodeFactory.instance.arrayNode();
    requestedFields
        .addObject()
        .put("key", "clarification")
        .put("label", "Clarification")
        .put("type", "TEXT")
        .put("required", true)
        .put("sensitive", true);

    TaskDtos.TaskItemView result =
        facade.executeAction(
            task.getId(),
            "request-revision",
            new ExpectedVersion(0),
            new TaskDtos.TaskActionRequest(
                "Please clarify", null, null, requestedFields, targetNodeId),
            new CommandId(UUID.randomUUID()),
            new CorrelationId(UUID.randomUUID()));

    assertThat(result.id()).isEqualTo(task.getId());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<TaskRevisionRequestPort.FieldSpec>> fieldsCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(revisionRequestService)
        .open(
            eq(task.getId()),
            eq(targetNodeId),
            eq("Please clarify"),
            fieldsCaptor.capture(),
            any(),
            any());
    assertThat(fieldsCaptor.getValue()).hasSize(1);
    TaskRevisionRequestPort.FieldSpec field = fieldsCaptor.getValue().getFirst();
    assertThat(field.key()).isEqualTo("clarification");
    assertThat(field.type()).isEqualTo("TEXT");
    assertThat(field.required()).isTrue();
    assertThat(field.sensitive()).isTrue();
  }

  @Test
  void requestRevisionRequiresExpectedTaskVersion() {
    TaskDtos.TaskActionRequest request =
        new TaskDtos.TaskActionRequest(
            "Please clarify", null, null, JsonNodeFactory.instance.arrayNode(), null);

    assertThatThrownBy(
            () ->
                facade.executeAction(
                    task.getId(),
                    "request-revision",
                    null,
                    request,
                    new CommandId(UUID.randomUUID()),
                    new CorrelationId(UUID.randomUUID())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("If-Match");
    verify(commandExecutor, never()).execute(any(), any());
  }
}
