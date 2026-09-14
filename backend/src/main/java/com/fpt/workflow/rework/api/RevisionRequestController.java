package com.fpt.workflow.rework.api;

import com.fpt.workflow.rework.dto.RevisionRequestDtos;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Ticket creator resubmission for an OPEN RevisionRequest (v2.4.1 §7.5). Command-wrapped for
 * idempotency, If-Match expected version, authorization, and audit.
 */
@RestController
@RequestMapping("/api/v1/revision-requests")
@PreAuthorize("isAuthenticated()")
public class RevisionRequestController {
  private final RevisionSubmitCommandFacade submitCommands;
  private final UuidGenerator uuids;

  public RevisionRequestController(RevisionSubmitCommandFacade submitCommands, UuidGenerator uuids) {
    this.submitCommands = submitCommands;
    this.uuids = uuids;
  }

  @PostMapping("/{requestId}/submit")
  public RevisionRequestDtos.RevisionSubmitView submit(
      @PathVariable UUID requestId,
      @RequestHeader("X-Command-Id") UUID rawCommandId,
      @RequestHeader(value = "X-Correlation-Id", required = false) UUID rawCorrelationId,
      @RequestHeader("If-Match") long expectedVersion,
      @RequestBody(required = false) RevisionRequestDtos.SubmitRevisionRequest request) {
    CommandId commandId = new CommandId(rawCommandId);
    CorrelationId correlationId =
        new CorrelationId(rawCorrelationId != null ? rawCorrelationId : uuids.generate());
    RevisionRequestDtos.SubmitRevisionRequest body =
        request != null
            ? request
            : new RevisionRequestDtos.SubmitRevisionRequest(null, null, null);
    return submitCommands.submit(
        requestId,
        body.values(),
        body.replacementTicketData(),
        body.changeReason(),
        expectedVersion,
        correlationId,
        commandId);
  }
}
