package com.fpt.workflow.resolver.participant;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Closed runtime registry. Unknown resolver types/config properties fail instead of falling back.
 */
@Component
public final class ParticipantResolverRegistry {
  private final Map<String, ParticipantResolver> resolvers;

  public ParticipantResolverRegistry(List<ParticipantResolver> providers) {
    Map<String, ParticipantResolver> registered = new HashMap<>();
    for (ParticipantResolver provider : providers) {
      String key = normalize(provider.type());
      if (registered.putIfAbsent(key, provider) != null) {
        throw new IllegalStateException("Duplicate participant resolver type: " + key);
      }
    }
    resolvers = Map.copyOf(registered);
  }

  public UUID resolve(String type, ParticipantResolverContext context) {
    return require(type).resolve(context);
  }

  public List<ParticipantResolverValidationIssue> validate(JsonNode config) {
    if (config == null || !config.isObject()) {
      return List.of(
          new ParticipantResolverValidationIssue(
              "PARTICIPANT_CONFIG_REQUIRED", "/participant", "Participant config is required"));
    }
    if (!config.hasNonNull("type") || !config.path("type").isTextual()) {
      return List.of(
          new ParticipantResolverValidationIssue(
              "PARTICIPANT_TYPE_REQUIRED",
              "/participant/type",
              "Participant resolver type is required"));
    }
    ParticipantResolver resolver;
    try {
      resolver = require(config.path("type").asText());
    } catch (IllegalArgumentException exception) {
      return List.of(
          new ParticipantResolverValidationIssue(
              "PARTICIPANT_TYPE_UNKNOWN", "/participant/type", exception.getMessage()));
    }
    List<ParticipantResolverValidationIssue> issues = new ArrayList<>();
    Set<String> allowed = resolver.configProperties();
    config
        .fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) {
                issues.add(
                    new ParticipantResolverValidationIssue(
                        "PARTICIPANT_CONFIG_UNKNOWN_PROPERTY",
                        "/participant/" + name,
                        "Property is not declared by resolver " + resolver.type()));
              }
            });
    return List.copyOf(issues);
  }

  private ParticipantResolver require(String type) {
    String key = normalize(type);
    ParticipantResolver resolver = resolvers.get(key);
    if (resolver == null) {
      throw new IllegalArgumentException("Unknown participant resolver type: " + key);
    }
    return resolver;
  }

  private static String normalize(String type) {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("Participant resolver type is required");
    }
    return type.trim().toUpperCase(Locale.ROOT);
  }
}
