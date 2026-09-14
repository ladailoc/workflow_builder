package com.fpt.workflow.resolver.participant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeOutputParticipantResolverTest {
  @Test
  void readsLatestOccurrenceOutputFromCanonicalEventContext() {
    UUID expected = UUID.randomUUID();
    ObjectNode config = JsonNodeFactory.instance.objectNode();
    config.put("type", "NODE_OUTPUT");
    config.put("nodeKey", "managerReview");
    config.put("outputPath", "ownerId");
    ObjectNode nodes = JsonNodeFactory.instance.objectNode();
    nodes
        .putObject("managerReview")
        .putObject("latest")
        .putObject("output")
        .put("ownerId", expected.toString());
    ParticipantResolverContext context =
        new ParticipantResolverContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            config,
            Instant.parse("2026-09-12T00:00:00Z"),
            JsonNodeFactory.instance.objectNode(),
            nodes,
            null);

    var result = new NodeOutputParticipantResolver().resolveResult(context);

    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.RESOLVED);
    assertThat(result.users()).containsExactly(expected);
  }
}
