package com.fpt.workflow.integration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.connector.service.ConnectorUrlSecurityValidator;
import com.fpt.workflow.integration.domain.IntegrationErrorCategory;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DefaultConnectorActionClient implements ConnectorActionClient {

  private final ConnectorUrlSecurityValidator urlSecurityValidator;
  private volatile ConnectorActionClient testDelegate;

  public DefaultConnectorActionClient() {
    this(new ConnectorUrlSecurityValidator());
  }

  @Autowired
  public DefaultConnectorActionClient(ConnectorUrlSecurityValidator urlSecurityValidator) {
    this.urlSecurityValidator =
        Objects.requireNonNull(urlSecurityValidator, "urlSecurityValidator");
  }

  public void setTestDelegate(ConnectorActionClient delegate) {
    this.testDelegate = delegate;
  }

  public void clearTestDelegate() {
    this.testDelegate = null;
  }

  @Override
  public IntegrationCallResponse execute(IntegrationCallRequest request) {
    // 1. SSRF & Security Validation immediately before outbound execution (§17.2 / §29.1)
    validateSecurity(request);

    // 2. Delegate to configured test adapter if present
    if (testDelegate != null) {
      return testDelegate.execute(request);
    }

    var details = JsonNodeFactory.instance.objectNode();
    details.put("connectorKey", request.connectorKey());
    details.put("actionKey", request.actionKey());
    details.put("actionVersion", request.actionVersion());
    return IntegrationCallResponse.failure(
        IntegrationErrorCategory.CONFIGURATION_ERROR,
        0,
        "No ConnectorActionClient adapter is registered for this action",
        details);
  }

  private void validateSecurity(IntegrationCallRequest request) {
    JsonNode execConfig = request.executionConfig();
    JsonNode inputData = request.inputData();

    Set<String> allowedHosts = extractStringSet(execConfig, "allowedHosts");
    Set<String> allowedMethods = extractStringSet(execConfig, "allowedMethods");

    String httpMethod = null;
    if (execConfig != null) {
      if (execConfig.hasNonNull("method")) {
        httpMethod = execConfig.path("method").asText();
      } else if (execConfig.hasNonNull("httpMethod")) {
        httpMethod = execConfig.path("httpMethod").asText();
      }
    }

    // Check candidate URLs in executionConfig
    validateCandidateUrl(execConfig, "url", allowedHosts, httpMethod, allowedMethods);
    validateCandidateUrl(execConfig, "baseUrl", allowedHosts, httpMethod, allowedMethods);
    validateCandidateUrl(execConfig, "endpoint", allowedHosts, httpMethod, allowedMethods);
    validateCandidateUrl(execConfig, "targetUrl", allowedHosts, httpMethod, allowedMethods);

    // Check candidate URLs in inputData
    validateCandidateUrl(inputData, "url", allowedHosts, httpMethod, allowedMethods);
    validateCandidateUrl(inputData, "endpoint", allowedHosts, httpMethod, allowedMethods);
  }

  private void validateCandidateUrl(
      JsonNode container,
      String fieldName,
      Set<String> allowedHosts,
      String httpMethod,
      Set<String> allowedMethods) {
    if (container == null || !container.hasNonNull(fieldName)) {
      return;
    }
    String val = container.path(fieldName).asText();
    if (val != null && (val.startsWith("http://") || val.startsWith("https://") || val.contains("://"))) {
      urlSecurityValidator.validate(val, allowedHosts, httpMethod, allowedMethods);
    }
  }

  private Set<String> extractStringSet(JsonNode node, String fieldName) {
    if (node == null || !node.hasNonNull(fieldName)) {
      return null;
    }
    JsonNode arr = node.path(fieldName);
    if (!arr.isArray()) {
      return null;
    }
    Set<String> result = new HashSet<>();
    for (JsonNode item : arr) {
      if (item.isTextual() && !item.asText().isBlank()) {
        result.add(item.asText().trim());
      }
    }
    return result;
  }
}
