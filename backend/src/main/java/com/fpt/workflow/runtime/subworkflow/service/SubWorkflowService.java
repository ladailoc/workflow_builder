package com.fpt.workflow.runtime.subworkflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.nodetype.NodeExecutionResult;
import com.fpt.workflow.runtime.activation.ActivationRequest;
import com.fpt.workflow.runtime.context.RuntimeScope;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.time.Instant;
import java.util.UUID;

public interface SubWorkflowService {

  boolean isSubWorkflowNode(NodeDefinition node);

  NodeExecutionResult activateSubWorkflow(
      Event parentEvent,
      NodeDefinition node,
      NodeExecution parentNodeExecution,
      JsonNode input,
      ActivationRequest request,
      RuntimeScope scope);

  void onChildEventTerminal(Event childEvent, CorrelationId correlationId, CommandId commandId);

  void handleParentCancellation(UUID parentNodeExecutionId, Instant now);
}
