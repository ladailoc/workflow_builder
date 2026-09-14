package com.fpt.workflow.operations.retention;

import com.fpt.workflow.shared.api.RequestCorrelationFilter;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/retention")
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class PlatformRetentionController {

  public static final String COMMAND_ID_HEADER = "X-Command-Id";
  private final PlatformRetentionExecutor executor;
  private final PlatformRetentionCommandService commands;

  public PlatformRetentionController(
      PlatformRetentionExecutor executor, PlatformRetentionCommandService commands) {
    this.executor = executor;
    this.commands = commands;
  }

  @PostMapping("/preview")
  public PlatformRetentionExecutor.RetentionPreview preview(
      @Valid @RequestBody RetentionRequest request) {
    return executor.preview(request.toCandidates());
  }

  @PostMapping("/execute")
  public PlatformRetentionCommandService.RetentionJobSubmission execute(
      @RequestHeader(COMMAND_ID_HEADER) UUID commandId,
      @Valid @RequestBody RetentionRequest request,
      HttpServletRequest httpRequest) {
    return commands.enqueue(
        request.toCandidates(),
        CorrelationId.parse(RequestCorrelationFilter.correlationId(httpRequest)),
        new CommandId(commandId));
  }

  public record RetentionRequest(@NotEmpty List<@Valid Candidate> candidates) {
    List<PlatformRetentionExecutor.RetentionCandidate> toCandidates() {
      return candidates.stream()
          .map(
              candidate ->
                  new PlatformRetentionExecutor.RetentionCandidate(
                      candidate.category(),
                      candidate.aggregateType(),
                      candidate.aggregateId(),
                      candidate.artifactTime(),
                      candidate.runtimeHistoryReferenced()))
          .toList();
    }
  }

  public record Candidate(
      @NotNull RetentionCategory category,
      @NotNull String aggregateType,
      @NotNull UUID aggregateId,
      @NotNull Instant artifactTime,
      boolean runtimeHistoryReferenced) {}
}
