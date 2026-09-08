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
}
