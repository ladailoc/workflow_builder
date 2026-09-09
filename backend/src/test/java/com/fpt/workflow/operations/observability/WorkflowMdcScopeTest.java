package com.fpt.workflow.operations.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class WorkflowMdcScopeTest {

  @BeforeEach
  @AfterEach
  void cleanMdc() {
    MDC.clear();
  }

  @Test
  void setsAndCleansMdcEntriesInScope() {
    UUID eventId = UUID.randomUUID();
    UUID nodeExecutionId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    UUID commandId = UUID.randomUUID();
    UUID integrationExecutionId = UUID.randomUUID();
    String reqId = "req-123";
    String corrId = "corr-456";

    try (var scope =
        WorkflowMdcScope.builder()
            .requestId(reqId)
            .correlationId(corrId)
            .eventId(eventId)
            .nodeExecutionId(nodeExecutionId)
            .taskId(taskId)
            .commandId(commandId)
            .integrationExecutionId(integrationExecutionId)
            .open()) {

      assertThat(MDC.get("requestId")).isEqualTo(reqId);
      assertThat(MDC.get("correlationId")).isEqualTo(corrId);
      assertThat(MDC.get("eventId")).isEqualTo(eventId.toString());
      assertThat(MDC.get("nodeExecutionId")).isEqualTo(nodeExecutionId.toString());
      assertThat(MDC.get("taskId")).isEqualTo(taskId.toString());
      assertThat(MDC.get("commandId")).isEqualTo(commandId.toString());
      assertThat(MDC.get("integrationExecutionId")).isEqualTo(integrationExecutionId.toString());
    }

    // After scope closes, all keys are cleared
    assertThat(MDC.get("requestId")).isNull();
    assertThat(MDC.get("correlationId")).isNull();
    assertThat(MDC.get("eventId")).isNull();
    assertThat(MDC.get("nodeExecutionId")).isNull();
    assertThat(MDC.get("taskId")).isNull();
    assertThat(MDC.get("commandId")).isNull();
    assertThat(MDC.get("integrationExecutionId")).isNull();
  }

  @Test
  void restoresOuterScopeValuesWhenInnerScopeCloses() {
    UUID outerEventId = UUID.randomUUID();
    UUID innerNodeId = UUID.randomUUID();

    try (var outer = WorkflowMdcScope.forEvent(outerEventId)) {
      assertThat(MDC.get("eventId")).isEqualTo(outerEventId.toString());

      try (var inner = WorkflowMdcScope.forNodeExecution(outerEventId, innerNodeId)) {
        assertThat(MDC.get("eventId")).isEqualTo(outerEventId.toString());
        assertThat(MDC.get("nodeExecutionId")).isEqualTo(innerNodeId.toString());
      }

      // Inner closed: nodeId should be cleared, outer eventId remains
      assertThat(MDC.get("nodeExecutionId")).isNull();
      assertThat(MDC.get("eventId")).isEqualTo(outerEventId.toString());
    }

    assertThat(MDC.get("eventId")).isNull();
  }

  @Test
  void masksSensitiveValues() {
    assertThat(SensitiveDataMasker.isSensitiveKey("password")).isTrue();
    assertThat(SensitiveDataMasker.isSensitiveKey("apiKey")).isTrue();
    assertThat(SensitiveDataMasker.isSensitiveKey("api_key")).isTrue();
    assertThat(SensitiveDataMasker.isSensitiveKey("authToken")).isTrue();
    assertThat(SensitiveDataMasker.isSensitiveKey("client_secret")).isTrue();
    assertThat(SensitiveDataMasker.isSensitiveKey("eventId")).isFalse();

    assertThat(SensitiveDataMasker.maskIfSensitive("password", "supersecret"))
        .isEqualTo(SensitiveDataMasker.REDACTED);

    String textWithBearer = "Authorization: Bearer my-secret-jwt-token-12345";
    assertThat(SensitiveDataMasker.maskString(textWithBearer))
        .isEqualTo("Authorization: Bearer " + SensitiveDataMasker.REDACTED);
  }
}
