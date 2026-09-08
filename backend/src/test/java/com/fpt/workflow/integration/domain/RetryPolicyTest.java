package com.fpt.workflow.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void nonIdempotentPolicy_neverRetries() {
    RetryPolicy policy =
        new RetryPolicy(
            false,
            3,
            10,
            Set.of(
                IntegrationErrorCategory.SERVICE_UNAVAILABLE_503,
                IntegrationErrorCategory.TIMEOUT));

    // Non-idempotent action: no blind automatic retry
    assertThat(policy.canRetry(IntegrationErrorCategory.SERVICE_UNAVAILABLE_503, 1)).isFalse();
    assertThat(policy.canRetry(IntegrationErrorCategory.TIMEOUT, 1)).isFalse();
    assertThat(policy.canRetry(IntegrationErrorCategory.LOST_RESPONSE, 1)).isFalse();
  }

  @Test
  void idempotentPolicy_retriesUntilMaxExhausted() {
    RetryPolicy policy =
        new RetryPolicy(
            true,
            2,
            10,
            Set.of(
                IntegrationErrorCategory.SERVICE_UNAVAILABLE_503,
                IntegrationErrorCategory.TIMEOUT));

    assertThat(policy.canRetry(IntegrationErrorCategory.SERVICE_UNAVAILABLE_503, 1)).isTrue();
    assertThat(policy.canRetry(IntegrationErrorCategory.SERVICE_UNAVAILABLE_503, 2)).isTrue();
    // Attempt 3 exceeds maxRetries (2) -> exhausted
    assertThat(policy.canRetry(IntegrationErrorCategory.SERVICE_UNAVAILABLE_503, 3)).isFalse();

    // Unclassified error is not retried
    assertThat(policy.canRetry(IntegrationErrorCategory.HTTP_4XX, 1)).isFalse();
  }

  @Test
  void parsesFromJsonCorrectly() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("idempotent", true);
    node.put("maxRetries", 4);
    node.put("backoffMs", 100);
    ArrayNode errors = node.putArray("retryableErrors");
    errors.add("TIMEOUT");
    errors.add("LOST_RESPONSE");

    RetryPolicy policy = RetryPolicy.fromJson(node);

    assertThat(policy.idempotent()).isTrue();
    assertThat(policy.maxRetries()).isEqualTo(4);
    assertThat(policy.backoffMs()).isEqualTo(100);
    assertThat(policy.retryableErrors())
        .containsExactlyInAnyOrder(
            IntegrationErrorCategory.TIMEOUT, IntegrationErrorCategory.LOST_RESPONSE);
  }
}
