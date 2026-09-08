package com.fpt.workflow.integration.client;

@FunctionalInterface
public interface ConnectorActionClient {

  IntegrationCallResponse execute(IntegrationCallRequest request);
}
