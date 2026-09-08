package com.fpt.workflow.integration.service;

import com.fpt.workflow.integration.domain.IntegrationExecution;
import com.fpt.workflow.runtime.domain.NodeExecution;
import com.fpt.workflow.runtime.routing.RoutingResult;

public record SystemActionResult(
    IntegrationExecution integrationExecution,
    NodeExecution nodeExecution,
    String outcomePort,
    RoutingResult routingResult) {}
