package com.fpt.workflow.resolver.participant;

import java.util.Set;
import java.util.UUID;

/** One generic participant-resolution primitive registered by stable resolver type. */
public interface ParticipantResolver {
  String type();

  Set<String> configProperties();

  UUID resolve(ParticipantResolverContext context);
}
