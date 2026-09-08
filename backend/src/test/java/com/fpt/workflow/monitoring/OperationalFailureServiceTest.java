package com.fpt.workflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.integration.domain.IntegrationExecutionStatus;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.operations.job.WorkflowJobRepository;
import com.fpt.workflow.operations.job.WorkflowJobStatus;
import com.fpt.workflow.operations.outbox.OutboxEventRepository;
import com.fpt.workflow.operations.outbox.OutboxStatus;
import com.fpt.workflow.slanotification.domain.NotificationDispatchStatus;
import com.fpt.workflow.slanotification.repository.NotificationDispatchRepository;
import org.junit.jupiter.api.Test;

class OperationalFailureServiceTest {

  @Test
  void queriesEveryOperationalTerminalFailureBucketWithoutPayloadExposure() {
    WorkflowJobRepository jobs = mock(WorkflowJobRepository.class);
    OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    NotificationDispatchRepository notifications = mock(NotificationDispatchRepository.class);
    IntegrationExecutionRepository integrations = mock(IntegrationExecutionRepository.class);
    var service =
        new OperationalFailureService(
            jobs, outbox, notifications, integrations, new ObjectMapper());

    assertThat(service.list()).isEmpty();

    verify(jobs).findAllByStatusOrderByUpdatedAtAsc(WorkflowJobStatus.DEAD);
    verify(outbox).findAllByStatusOrderByCreatedAtAsc(OutboxStatus.DEAD);
    verify(notifications).findAllByStatusOrderByUpdatedAtAsc(NotificationDispatchStatus.DEAD);
    verify(integrations).findAllByStatusOrderByUpdatedAtAsc(IntegrationExecutionStatus.FAILED);
    verify(integrations)
        .findAllByStatusOrderByUpdatedAtAsc(IntegrationExecutionStatus.MANUAL_RECONCILIATION);
  }
}
