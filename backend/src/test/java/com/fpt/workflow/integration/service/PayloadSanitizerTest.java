package com.fpt.workflow.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class PayloadSanitizerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void sanitizesSensitiveFieldsRecursively() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("connectorKey", "http-connector");
    node.put("actionKey", "call-api");
    node.put("password", "super-secret-pw");
    node.put("apiKey", "key-12345");
    node.put("bearerToken", "jwt-token-xyz");

    ObjectNode nested = node.putObject("nested");
    nested.put("safeProperty", "safe-value");
    nested.put("accessToken", "sensitive-access-token");

    var sanitized = PayloadSanitizer.sanitize(node);

    assertThat(sanitized.get("connectorKey").asText()).isEqualTo("http-connector");
    assertThat(sanitized.get("actionKey").asText()).isEqualTo("call-api");
    assertThat(sanitized.get("password").asText()).isEqualTo("***REDACTED***");
    assertThat(sanitized.get("apiKey").asText()).isEqualTo("***REDACTED***");
    assertThat(sanitized.get("bearerToken").asText()).isEqualTo("***REDACTED***");
    assertThat(sanitized.get("nested").get("safeProperty").asText()).isEqualTo("safe-value");
    assertThat(sanitized.get("nested").get("accessToken").asText()).isEqualTo("***REDACTED***");
  }
}
