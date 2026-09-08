package com.fpt.workflow.integration.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    // Default fallback: return a successful response with request echo
    ObjectNode response = JsonNodeFactory.instance.objectNode();
    response.put("status", "SUCCESS");
    response.put("connectorKey", request.connectorKey());
    response.put("actionKey", request.actionKey());
    response.put("actionVersion", request.actionVersion());
    response.put("idempotencyKey", request.idempotencyKey());
    if (request.inputData() != null) {
      response.set("echo", request.inputData());
    }
    return IntegrationCallResponse.success(200, response);
  }
}
