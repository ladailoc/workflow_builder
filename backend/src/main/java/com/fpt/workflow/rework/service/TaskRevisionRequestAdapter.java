package com.fpt.workflow.rework.service;

import com.fpt.workflow.rework.domain.RuntimeRequestedFieldType;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.task.service.TaskRevisionRequestPort;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Rework-side adapter for the task command port. */
@Component
public class TaskRevisionRequestAdapter implements TaskRevisionRequestPort {
  private final RevisionRequestService revisionRequests;

  public TaskRevisionRequestAdapter(RevisionRequestService revisionRequests) {
    this.revisionRequests = revisionRequests;
  }

  @Override
  public void open(
      UUID taskId,
      UUID targetNodeId,
      String comment,
      List<FieldSpec> requestedFields,
      CorrelationId correlationId,
      CommandId commandId) {
    List<RevisionRequestService.FieldSpec> fields =
        requestedFields.stream()
            .map(
                field ->
                    new RevisionRequestService.FieldSpec(
                        field.key(),
                        field.label(),
                        RuntimeRequestedFieldType.valueOf(field.type()),
                        field.required(),
                        field.sensitive(),
                        field.schema()))
            .toList();
    revisionRequests.open(taskId, targetNodeId, comment, fields, correlationId, commandId);
  }
}
