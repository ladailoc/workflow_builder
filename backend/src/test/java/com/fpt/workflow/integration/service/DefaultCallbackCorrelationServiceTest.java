package com.fpt.workflow.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.connector.domain.ConnectorDefinition;
import com.fpt.workflow.connector.repository.ConnectorDefinitionRepository;
import com.fpt.workflow.integration.domain.IntegrationCallback;
import com.fpt.workflow.integration.domain.IntegrationCallbackStatus;
import com.fpt.workflow.integration.repository.IntegrationCallbackRepository;
import com.fpt.workflow.integration.repository.IntegrationExecutionRepository;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.runtime.repository.EventRepository;
import com.fpt.workflow.runtime.repository.NodeExecutionRepository;
import com.fpt.workflow.runtime.routing.RoutingService;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultCallbackCorrelationServiceTest {

  @Test
  void activeConnectorWithoutSigningCredentialRejectsCallbackFailClosed() {
    Instant now = Instant.parse("2026-09-08T12:00:00Z");
    PlatformClock clock = mock(PlatformClock.class);
    when(clock.now()).thenReturn(now);
    UuidGenerator uuids = mock(UuidGenerator.class);
    when(uuids.generate()).thenReturn(UUID.randomUUID());
    ConnectorDefinitionRepository connectors = mock(ConnectorDefinitionRepository.class);
    when(connectors.findByKey("ERP"))
        .thenReturn(
            Optional.of(
                ConnectorDefinition.create(
                    UUID.randomUUID(),
                    "ERP",
                    "ERP",
                    "REST",
                    "erpHandler",
                    "ACTIVE",
                    JsonNodeFactory.instance.objectNode(),
                    "vault://missing",
                    now)));
    ConnectorCredentialProvider credentials = mock(ConnectorCredentialProvider.class);
    when(credentials.getSigningSecret("ERP", "vault://missing")).thenReturn(Optional.empty());
    IntegrationCallbackRepository callbacks = mock(IntegrationCallbackRepository.class);
    when(callbacks.findByConnectorKeyAndExternalEventId("ERP", "external-1"))
        .thenReturn(Optional.empty());
    when(callbacks.saveAndFlush(any(IntegrationCallback.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var service =
        new DefaultCallbackCorrelationService(
            clock,
            uuids,
            new ObjectMapper(),
            connectors,
            credentials,
            mock(CallbackSignatureValidator.class),
            callbacks,
            mock(IntegrationExecutionRepository.class),
            mock(AuditEventRepository.class),
            mock(NodeExecutionRepository.class),
            mock(EventRepository.class),
            mock(RoutingService.class));

    var result =
        service.processCallback(
            new CallbackCommand(
                "correlation-1",
                "ERP",
                "external-1",
                "signature",
                now,
                "{}",
                JsonNodeFactory.instance.objectNode(),
                null,
                null));

    assertThat(result.status()).isEqualTo(IntegrationCallbackStatus.REJECTED);
    assertThat(result.message()).contains("signing credential is not configured");
  }
}
