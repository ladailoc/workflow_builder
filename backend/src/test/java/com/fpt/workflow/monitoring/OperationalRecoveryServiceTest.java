package com.fpt.workflow.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.connector.repository.ConnectorActionVersionRepository;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.integration.service.SystemActionTransactionService;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.operations.command.CommandAction;
import com.fpt.workflow.operations.command.CommandExecutionResult;
import com.fpt.workflow.operations.command.CommandExecutor;
import com.fpt.workflow.operations.job.WorkflowJob;
import com.fpt.workflow.operations.job.WorkflowJobRepository;
import com.fpt.workflow.operations.job.WorkflowJobStatus;
import com.fpt.workflow.operations.job.WorkflowJobTransactions;
import com.fpt.workflow.runtime.activation.NodeActivationService;
import com.fpt.workflow.runtime.lifecycle.EventLifecycleService;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.security.RoleKey;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OperationalRecoveryServiceTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final CommandExecutor commands = mock(CommandExecutor.class);
  private final WorkflowJobRepository jobs = mock(WorkflowJobRepository.class);
  private final WorkflowJobTransactions jobTransactions = mock(WorkflowJobTransactions.class);
  private final AuditEventRepository audits = mock(AuditEventRepository.class);
  private final ActorContextProvider actors = mock(ActorContextProvider.class);
  private OperationalRecoveryService service;

  @BeforeEach
  void setUp() {
    UUID actorId = UUID.randomUUID();
    when(actors.requireActor())
        .thenReturn(new ActorContext(actorId, "operator", Set.of(RoleKey.OPERATOR), Set.of()));
    when(commands.execute(any(), any()))
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
    service =
        new OperationalRecoveryService(
            commands,
            jobs,
            jobTransactions,
            mock(NodeExecutionRepository.class),
            mock(NodeActivationService.class),
            mock(IntegrationExecutionRepository.class),
            mock(ConnectorActionVersionRepository.class),
            mock(SystemActionTransactionService.class),
            mock(ManualRecoveryTaskService.class),
            mock(EventRepository.class),
            mock(EventLifecycleService.class),
            audits,
            actors,
            UUID::randomUUID,
            () -> Instant.parse("2026-09-09T00:00:00Z"),
            mapper,
            new CanonicalDefinitionJson(mapper));
  }

  @Test
  void deadJobRetryRequiresReasonAndWritesActorAudit() {
    UUID jobId = UUID.randomUUID();
    WorkflowJob job = mock(WorkflowJob.class);
    when(job.getStatus()).thenReturn(WorkflowJobStatus.DEAD);
    when(jobs.findById(jobId)).thenReturn(Optional.of(job));
    when(jobTransactions.retryDead(jobId, 5)).thenReturn(true);
    UUID commandId = UUID.randomUUID();

    CommandExecutionResult result =
        service.retryJob(
            jobId,
            5,
            new OperationalRecoveryService.OverrideCommand(
                commandId, "verified worker outage", null, mapper.createObjectNode()),
            new CorrelationId(UUID.randomUUID()));

    assertThat(result.resultJson().path("status").asText()).isEqualTo("RETRY");
    verify(jobTransactions).retryDead(jobId, 5);
    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audits).save(audit.capture());
    assertThat(audit.getValue().getEventType()).isEqualTo("JOB_RETRY_REQUESTED");
    assertThat(audit.getValue().getActorId()).isNotNull();
    assertThat(audit.getValue().getMetadataJson().path("reason").asText())
        .isEqualTo("verified worker outage");
  }

  @Test
  void blankOverrideReasonStopsBeforeCommandReservation() {
    var request =
        new OperationalRecoveryService.OverrideCommand(
            UUID.randomUUID(), "  ", null, mapper.createObjectNode());
    assertThatThrownBy(
            () ->
                service.retryJob(
                    UUID.randomUUID(), 0, request, new CorrelationId(UUID.randomUUID())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");
    verify(commands, never()).execute(any(), any());
  }
}
