package com.fpt.workflow.task.api;

import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.task.dto.TaskDtos;
import com.fpt.workflow.task.service.TaskQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

  public static final String COMMAND_ID_HEADER = "X-Command-Id";
  public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  private final TaskQueryService taskQueryService;
  private final UuidGenerator uuidGenerator;

  public TaskController(TaskQueryService taskQueryService, UuidGenerator uuidGenerator) {
    this.taskQueryService = taskQueryService;
    this.uuidGenerator = uuidGenerator;
  }

  @GetMapping
  public List<TaskDtos.TaskItemView> list(@RequestParam(required = false) String status) {
    return taskQueryService.listTasks(status);
  }

  @PostMapping("/{taskId}/{action}")
  public TaskDtos.TaskItemView executeAction(
      @PathVariable UUID taskId,
      @PathVariable String action,
      @RequestHeader(value = COMMAND_ID_HEADER, required = false) UUID rawCommandId,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) UUID rawCorrelationId,
      @RequestBody(required = false) TaskDtos.TaskActionRequest request) {

    CommandId commandId =
        new CommandId(rawCommandId != null ? rawCommandId : uuidGenerator.generate());
    CorrelationId correlationId =
        new CorrelationId(rawCorrelationId != null ? rawCorrelationId : uuidGenerator.generate());

    return taskQueryService.executeAction(taskId, action, request, commandId, correlationId);
  }
}
