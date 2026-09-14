package com.fpt.workflow.resolver.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Normative resolution result contract per workflow_spec.md §9.4:
 * ResolutionResult.status = RESOLVED | VACANT | INACTIVE_ASSIGNEE | NOT_FOUND | AMBIGUOUS
 * users = [...]
 */
public record ParticipantResolutionResult(
    ParticipantResolutionStatus status,
    List<UUID> users,
    String reason,
    String resolverType) {

  public ParticipantResolutionResult {
    Objects.requireNonNull(status, "status");
    users = users == null ? List.of() : List.copyOf(users);
  }

  public static ParticipantResolutionResult resolved(List<UUID> users, String resolverType) {
    if (users == null || users.isEmpty()) {
      return notFound("No matching users resolved for " + resolverType, resolverType);
    }
    return new ParticipantResolutionResult(
        ParticipantResolutionStatus.RESOLVED, users, null, resolverType);
  }

  public static ParticipantResolutionResult resolved(UUID user, String resolverType) {
    Objects.requireNonNull(user, "user");
    return new ParticipantResolutionResult(
        ParticipantResolutionStatus.RESOLVED, List.of(user), null, resolverType);
  }

  public static ParticipantResolutionResult vacant(String reason, String resolverType) {
    return new ParticipantResolutionResult(
        ParticipantResolutionStatus.VACANT, List.of(), reason, resolverType);
  }

  public static ParticipantResolutionResult inactive(
      UUID inactiveUser, String reason, String resolverType) {
    List<UUID> list = inactiveUser != null ? List.of(inactiveUser) : List.of();
    return new ParticipantResolutionResult(
        ParticipantResolutionStatus.INACTIVE_ASSIGNEE, list, reason, resolverType);
  }

  public static ParticipantResolutionResult notFound(String reason, String resolverType) {
    return new ParticipantResolutionResult(
        ParticipantResolutionStatus.NOT_FOUND, List.of(), reason, resolverType);
  }

  public static ParticipantResolutionResult ambiguous(
      List<UUID> candidates, String reason, String resolverType) {
    return new ParticipantResolutionResult(
        ParticipantResolutionStatus.AMBIGUOUS, candidates, reason, resolverType);
  }

  public static ParticipantResolutionResult failed(String reason, String resolverType) {
    return new ParticipantResolutionResult(
        ParticipantResolutionStatus.FAILED, List.of(), reason, resolverType);
  }

  public boolean isResolved() {
    return status == ParticipantResolutionStatus.RESOLVED && !users.isEmpty();
  }

  public Optional<UUID> singleUser() {
    return users.size() == 1 ? Optional.of(users.getFirst()) : Optional.empty();
  }

  public Optional<UUID> primaryUser() {
    return users.isEmpty() ? Optional.empty() : Optional.of(users.getFirst());
  }
}
