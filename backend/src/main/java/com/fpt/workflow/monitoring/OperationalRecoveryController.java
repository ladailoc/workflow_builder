package com.fpt.workflow.monitoring;

import com.fpt.workflow.operations.command.CommandExecutionResult;
import com.fpt.workflow.shared.api.RequestCorrelationFilter;
import com.fpt.workflow.shared.domain.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
public class OperationalRecoveryController {
  private final OperationalRecoveryService service;

  public OperationalRecoveryController(OperationalRecoveryService service) {
    this.service = service;
  }

  @PostMapping("/operations/jobs/{id}/retry")
  public CommandExecutionResult retryJob(
      @PathVariable UUID id,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody OperationalRecoveryService.OverrideCommand command,
      HttpServletRequest request) {
    return service.retryJob(id, expectedVersion, command, correlation(request));
  }

  @PostMapping("/node-executions/{id}/retry")
  public CommandExecutionResult retryNode(
      @PathVariable UUID id,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody OperationalRecoveryService.OverrideCommand command,
      HttpServletRequest request) {
    return service.retryNode(id, expectedVersion, command, correlation(request));
  }

  @PostMapping("/integrations/{id}/retry")
  public CommandExecutionResult retryIntegration(
      @PathVariable UUID id,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody OperationalRecoveryService.OverrideCommand command,
      HttpServletRequest request) {
    return service.retryIntegration(id, expectedVersion, command, correlation(request));
  }

  @PostMapping("/integrations/{id}/resolve")
  public CommandExecutionResult resolveIntegration(
      @PathVariable UUID id,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody OperationalRecoveryService.OverrideCommand command,
      HttpServletRequest request) {
    return service.resolveIntegration(id, expectedVersion, command, correlation(request));
  }

  @PostMapping("/integrations/{id}/manual-task")
  public CommandExecutionResult createManualTask(
      @PathVariable UUID id,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody OperationalRecoveryService.OverrideCommand command,
      HttpServletRequest request) {
    return service.createManualTask(id, expectedVersion, command, correlation(request));
  }

  @PostMapping("/events/{id}/terminate")
  public CommandExecutionResult terminateEvent(
      @PathVariable UUID id,
      @RequestHeader("If-Match") long expectedVersion,
      @Valid @RequestBody OperationalRecoveryService.OverrideCommand command,
      HttpServletRequest request) {
    return service.terminateEvent(id, expectedVersion, command, correlation(request));
  }

  private CorrelationId correlation(HttpServletRequest request) {
    return CorrelationId.parse(RequestCorrelationFilter.correlationId(request));
  }
}
