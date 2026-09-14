package com.fpt.workflow.resolver.participant;

import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import java.util.Set;
import java.util.UUID;

/** One generic participant-resolution primitive registered by stable resolver type. */
public interface ParticipantResolver {
  String type();

  Set<String> configProperties();

  UUID resolve(ParticipantResolverContext context);

  default ParticipantResolutionResult resolveResult(ParticipantResolverContext context) {
    try {
      UUID user = resolve(context);
      if (user == null) {
        return ParticipantResolutionResult.notFound("Null user resolved", type());
      }
      return ParticipantResolutionResult.resolved(user, type());
    } catch (Exception ex) {
      return ParticipantResolutionResult.failed(ex.getMessage(), type());
    }
  }
}
