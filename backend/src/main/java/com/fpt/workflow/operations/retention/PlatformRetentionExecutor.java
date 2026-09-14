package com.fpt.workflow.operations.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.operations.audit.AuditEvent;
import com.fpt.workflow.operations.audit.AuditEventRepository;
import com.fpt.workflow.security.ActorContext;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.domain.CommandId;
import com.fpt.workflow.shared.domain.CorrelationId;
import com.fpt.workflow.shared.time.PlatformClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Privileged retention executor (P2-15 §29.2). Decides via {@link RetentionPlanner} and records an
 * immutable decision row in {@code retention_actions}; actual anonymization/masking of artifact
 * payloads happens through category-scoped mutators supplied by domain owners. The planner's
 * no-hard-delete guarantee holds: artifacts still referenced by runtime history are downgraded to
 * ANONYMIZE, and tombstones/audit rows are retained instead of being hard-deleted.
 */
@Service
public class PlatformRetentionExecutor {

  private final RetentionPlanner planner;
  private final RetentionPolicyRepository policies;
  private final com.fpt.workflow.operations.retention.RetentionActionRepository actions;
  private final RetentionArtifactMutator mutator;
  private final AuditEventRepository audits;
  private final UuidGenerator uuids;
  private final PlatformClock clock;

  @Autowired
  public PlatformRetentionExecutor(
      RetentionPlanner planner,
      RetentionPolicyRepository policies,
      com.fpt.workflow.operations.retention.RetentionActionRepository actions,
      RetentionArtifactMutator mutator,
      AuditEventRepository audits,
      UuidGenerator uuids,
      PlatformClock clock) {
    this.planner = planner;
    this.policies = policies;
    this.actions = actions;
    this.mutator = mutator;
    this.audits = audits;
    this.uuids = uuids;
    this.clock = clock;
  }

  /**
   * Dry-run preview: computes what the executor would do for the given artifacts without
   * persisting any decision. Privileged visibility only.
   */
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  @Transactional(readOnly = true)
  public RetentionPreview preview(List<RetentionCandidate> candidates) {
    Instant now = clock.now();
    List<RetentionPreviewRow> rows = new ArrayList<>();
    for (RetentionCandidate candidate : candidates) {
      RetentionDecision decision = decide(candidate, now);
      if (decision != RetentionDecision.RETAIN
          && mutator.hasLegalHold(
              candidate.category(), candidate.aggregateType(), candidate.aggregateId())) {
        decision = RetentionDecision.RETAIN;
      }
      rows.add(
          new RetentionPreviewRow(
              candidate.category(), candidate.aggregateType(), candidate.aggregateId(), decision));
    }
    return new RetentionPreview(List.copyOf(rows));
  }

  /**
   * Executes retention for the given artifacts. Each decision is persisted in
   * {@code retention_actions}; every executed action is audited with the invoking actor.
   * Privileged invocation only (ADMIN/OPERATOR) and audit-backed.
   */
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  @Transactional
  public RetentionExecutionResult execute(
      List<RetentionCandidate> candidates,
      ActorContext actor,
      CorrelationId correlationId,
      CommandId commandId) {
    return executeFromDurableJob(candidates, actor, correlationId, commandId);
  }

  @Transactional
  public RetentionExecutionResult executeFromDurableJob(
      List<RetentionCandidate> candidates,
      ActorContext actor,
      CorrelationId correlationId,
      CommandId commandId) {
    Instant now = clock.now();
    int executed = 0;
    List<RetentionExecutionRow> rows = new ArrayList<>();
    for (RetentionCandidate candidate : candidates) {
      RetentionDecision decision = decide(candidate, now);
      boolean rowExecuted = false;
      if (decision != RetentionDecision.RETAIN
          && !mutator.hasLegalHold(
              candidate.category(), candidate.aggregateType(), candidate.aggregateId())
          && !alreadyExecuted(candidate, decision)) {
        boolean mutated =
            mutator.mutate(
                candidate.category(),
                candidate.aggregateType(),
                candidate.aggregateId(),
                decision,
                now);
        if (mutated) {
          actions.save(
              com.fpt.workflow.operations.retention.RetentionAction.decide(
                  uuids.generate(),
                  policyIdFor(candidate.category()),
                  candidate.category().name(),
                  candidate.aggregateType(),
                  candidate.aggregateId(),
                  decision.name(),
                  "Retention executor "
                      + decision.name().toLowerCase()
                      + " ("
                      + actor.actorId()
                      + ")",
                  now));
          writeAudit(candidate, decision, actor, correlationId, commandId, now);
          executed++;
          rowExecuted = true;
        }
      }
      rows.add(
          new RetentionExecutionRow(
              candidate.aggregateType(), candidate.aggregateId(), decision, rowExecuted));
    }
    return new RetentionExecutionResult(executed, List.copyOf(rows), now);
  }

  private RetentionDecision decide(RetentionCandidate candidate, Instant now) {
    RetentionPolicy policy =
        policies.findByCategoryAndEnabledTrue(candidate.category()).orElse(null);
    if (policy == null) {
      return RetentionDecision.RETAIN;
    }
    return planner.decide(
        policy,
        candidate.artifactTime(),
        now,
        candidate.runtimeHistoryReferenced());
  }

  private UUID policyIdFor(RetentionCategory category) {
    return policies.findByCategoryAndEnabledTrue(category)
        .map(RetentionPolicy::getId)
        .orElseThrow(() -> new IllegalStateException("Missing enabled retention policy " + category));
  }

  private boolean alreadyExecuted(RetentionCandidate candidate, RetentionDecision decision) {
    return actions.findAllByAggregateIdOrderByDecidedAtAsc(candidate.aggregateId()).stream()
        .anyMatch(
            action ->
                action.getCategory().equals(candidate.category().name())
                    && action.getAggregateType().equals(candidate.aggregateType().toUpperCase())
                    && action.getAction().equals(decision.name()));
  }

  private void writeAudit(
      RetentionCandidate candidate,
      RetentionDecision decision,
      ActorContext actor,
      CorrelationId correlationId,
      CommandId commandId,
      Instant now) {
    ObjectNode metadata = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    metadata.put("category", candidate.category().name());
    metadata.put("aggregateType", candidate.aggregateType());
    metadata.put("aggregateId", candidate.aggregateId().toString());
    metadata.put("decision", decision.name());
    audits.save(
        AuditEvent.record(
            uuids.generate(),
            candidate.aggregateType(),
            candidate.aggregateId(),
            "RETENTION_EXECUTED",
            actor.actorId(),
            actor.actorId(),
            correlationId,
            commandId,
            metadata,
            now));
  }

  public record RetentionCandidate(
      RetentionCategory category,
      String aggregateType,
      UUID aggregateId,
      Instant artifactTime,
      boolean runtimeHistoryReferenced) {}

  public record RetentionPreviewRow(
      RetentionCategory category, String aggregateType, UUID aggregateId, RetentionDecision decision) {}

  public record RetentionPreview(List<RetentionPreviewRow> rows) {
    public RetentionPreview {
      rows = List.copyOf(rows);
    }
  }

  public record RetentionExecutionRow(
      String aggregateType, UUID aggregateId, RetentionDecision decision, boolean executed) {}

  public record RetentionExecutionResult(
      int executedCount, List<RetentionExecutionRow> rows, Instant executedAt) {
    public RetentionExecutionResult {
      rows = List.copyOf(rows);
    }
  }

  /** Applies MASK/ANONYMIZE to a JSON artifact payload (pure helper reused by executors). */
  public static ObjectNode maskPayload(ObjectNode payload) {
    payload
        .fieldNames()
        .forEachRemaining(
            name -> {
              JsonNode child = payload.get(name);
              if (child != null && child.isTextual()) {
                payload.put(name, "***MASKED***");
              }
            });
    return payload;
  }
}
