package com.fpt.workflow.integration.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.integration.domain.IntegrationErrorCategory;
import org.springframework.stereotype.Component;

@Component
public class DefaultConnectorActionClient implements ConnectorActionClient {

  private volatile ConnectorActionClient testDelegate;

  public void setTestDelegate(ConnectorActionClient delegate) {
    this.testDelegate = delegate;
  }

  public void clearTestDelegate() {
    this.testDelegate = null;
  }

  @Override
  public IntegrationCallResponse execute(IntegrationCallRequest request) {
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
}
