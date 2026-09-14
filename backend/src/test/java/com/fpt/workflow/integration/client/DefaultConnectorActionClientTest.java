package com.fpt.workflow.integration.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.integration.domain.IntegrationErrorCategory;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultConnectorActionClientTest {

  @Test
  void missingProductionAdapterFailsClosed() {
    var client = new DefaultConnectorActionClient();
    var request =
        new IntegrationCallRequest(
            UUID.randomUUID(),
            "ERP",
            "CREATE_PO",
            1,
            "idempotency-key",
            JsonNodeFactory.instance.objectNode(),
            "vault://erp",
            JsonNodeFactory.instance.objectNode());

    var response = client.execute(request);

    assertThat(response.success()).isFalse();
    assertThat(response.errorCategory()).isEqualTo(IntegrationErrorCategory.CONFIGURATION_ERROR);
    assertThat(response.errorMessage()).contains("No ConnectorActionClient adapter");
  }

  @Test
  void ssrfValidation_blocksForbiddenUrlEvenWithTestDelegate() {
    var client = new DefaultConnectorActionClient();
    client.setTestDelegate(req -> IntegrationCallResponse.success(200, JsonNodeFactory.instance.objectNode()));

    var config = JsonNodeFactory.instance.objectNode();
    config.put("url", "http://169.254.169.254/latest/meta-data");

    var request =
        new IntegrationCallRequest(
            UUID.randomUUID(),
            "ERP",
            "METADATA",
            1,
            "idemp",
            JsonNodeFactory.instance.objectNode(),
            null,
            config);

    org.junit.jupiter.api.Assertions.assertThrows(
        SecurityException.class, () -> client.execute(request));
  }

  @Test
  void ssrfValidation_enforcesHostAllowlist() {
    var client = new DefaultConnectorActionClient();
    client.setTestDelegate(req -> IntegrationCallResponse.success(200, JsonNodeFactory.instance.objectNode()));

    var config = JsonNodeFactory.instance.objectNode();
    config.put("url", "https://evil.com/webhook");
    var allowedHosts = config.putArray("allowedHosts");
    allowedHosts.add("partner.org");

    var request =
        new IntegrationCallRequest(
            UUID.randomUUID(),
            "ERP",
            "WEBHOOK",
            1,
            "idemp",
            JsonNodeFactory.instance.objectNode(),
            null,
            config);

    org.junit.jupiter.api.Assertions.assertThrows(
        SecurityException.class, () -> client.execute(request));
  }
}
