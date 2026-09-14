package com.fpt.workflow.resolver.participant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.resolver.domain.ParticipantResolutionResult;
import com.fpt.workflow.resolver.domain.ParticipantResolutionStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpressionParticipantResolverTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private ExpressionParticipantResolver resolver;
  private UUID creatorId;
  private Instant now;

  @BeforeEach
  void setUp() {
    resolver = new ExpressionParticipantResolver();
    creatorId = UUID.randomUUID();
    now = Instant.parse("2026-09-10T12:00:00Z");
  }

  @Test
  void resolvesItemProperty_withItemPrefix() {
    UUID expectedUser = UUID.randomUUID();
    ObjectNode item = objectMapper.createObjectNode();
    item.put("employee", expectedUser.toString());

    ObjectNode config = objectMapper.createObjectNode();
    config.put("type", "EXPRESSION");
    config.put("expression", "${item.employee}");

    ParticipantResolverContext ctx =
        new ParticipantResolverContext(creatorId, creatorId, item, config, now);

    ParticipantResolutionResult result = resolver.resolveResult(ctx);
    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.RESOLVED);
    assertThat(result.users()).containsExactly(expectedUser);
  }

  @Test
  void resolvesItemProperty_withConfiguredItemVariablePrefix() {
    UUID expectedUser = UUID.randomUUID();
    ObjectNode item = objectMapper.createObjectNode();
    item.put("id", expectedUser.toString());
    item.put("name", "Alice");

    ObjectNode config = objectMapper.createObjectNode();
    config.put("type", "EXPRESSION");
    config.put("expression", "${employee.id}");

    ParticipantResolverContext ctx =
        new ParticipantResolverContext(creatorId, creatorId, item, config, now);

    ParticipantResolutionResult result = resolver.resolveResult(ctx);
    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.RESOLVED);
    assertThat(result.users()).containsExactly(expectedUser);
  }

  @Test
  void resolvesItemTextNode_whenReferencingItemVariable() {
    UUID expectedUser = UUID.randomUUID();
    var item = objectMapper.getNodeFactory().textNode(expectedUser.toString());

    ObjectNode config = objectMapper.createObjectNode();
    config.put("type", "EXPRESSION");
    config.put("expression", "${employee}");

    ParticipantResolverContext ctx =
        new ParticipantResolverContext(creatorId, creatorId, item, config, now);

    ParticipantResolutionResult result = resolver.resolveResult(ctx);
    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.RESOLVED);
    assertThat(result.users()).containsExactly(expectedUser);
  }

  @Test
  void resolvesItemObject_whenReferencingItemDirectly() {
    UUID expectedUser = UUID.randomUUID();
    ObjectNode item = objectMapper.createObjectNode();
    item.put("userId", expectedUser.toString());

    ObjectNode config = objectMapper.createObjectNode();
    config.put("type", "EXPRESSION");
    config.put("expression", "${item}");

    ParticipantResolverContext ctx =
        new ParticipantResolverContext(creatorId, creatorId, item, config, now);

    ParticipantResolutionResult result = resolver.resolveResult(ctx);
    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.RESOLVED);
    assertThat(result.users()).containsExactly(expectedUser);
  }

  @Test
  void resolvesUsingValueProperty_insteadOfExpression() {
    UUID expectedUser = UUID.randomUUID();
    ObjectNode item = objectMapper.createObjectNode();
    item.put("employeeId", expectedUser.toString());

    ObjectNode config = objectMapper.createObjectNode();
    config.put("type", "EXPRESSION");
    config.put("value", "${item.employeeId}");

    ParticipantResolverContext ctx =
        new ParticipantResolverContext(creatorId, creatorId, item, config, now);

    ParticipantResolutionResult result = resolver.resolveResult(ctx);
    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.RESOLVED);
    assertThat(result.users()).containsExactly(expectedUser);
  }

  @Test
  void resolvesTicketData_withTicketDataPrefix() {
    UUID expectedUser = UUID.randomUUID();
    ObjectNode ticketData = objectMapper.createObjectNode();
    ticketData.put("approverId", expectedUser.toString());

    ObjectNode config = objectMapper.createObjectNode();
    config.put("type", "EXPRESSION");
    config.put("expression", "${ticket.data.approverId}");

    ParticipantResolverContext ctx =
        new ParticipantResolverContext(
            creatorId,
            creatorId,
            null,
            config,
            now,
            ticketData,
            objectMapper.createObjectNode(),
            creatorId);

    ParticipantResolutionResult result = resolver.resolveResult(ctx);
    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.RESOLVED);
    assertThat(result.users()).containsExactly(expectedUser);
  }

  @Test
  void resolvesArrayOfUsers() {
    UUID user1 = UUID.randomUUID();
    UUID user2 = UUID.randomUUID();
    ObjectNode item = objectMapper.createObjectNode();
    ArrayNode array = item.putArray("reviewers");
    array.add(user1.toString());
    array.add(user2.toString());

    ObjectNode config = objectMapper.createObjectNode();
    config.put("type", "EXPRESSION");
    config.put("expression", "${item.reviewers}");

    ParticipantResolverContext ctx =
        new ParticipantResolverContext(creatorId, creatorId, item, config, now);

    ParticipantResolutionResult result = resolver.resolveResult(ctx);
    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.RESOLVED);
    assertThat(result.users()).containsExactly(user1, user2);
  }

  @Test
  void returnsNotFound_whenExpressionCannotBeResolved() {
    ObjectNode item = objectMapper.createObjectNode();
    item.put("other", "value");

    ObjectNode config = objectMapper.createObjectNode();
    config.put("type", "EXPRESSION");
    config.put("expression", "${employee.nonExistent}");

    ParticipantResolverContext ctx =
        new ParticipantResolverContext(creatorId, creatorId, item, config, now);

    ParticipantResolutionResult result = resolver.resolveResult(ctx);
    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.NOT_FOUND);
  }

  @Test
  void returnsFailed_whenExpressionIsBlank() {
    ObjectNode config = objectMapper.createObjectNode();
    config.put("type", "EXPRESSION");
    config.put("expression", "   ");

    ParticipantResolverContext ctx =
        new ParticipantResolverContext(creatorId, creatorId, null, config, now);

    ParticipantResolutionResult result = resolver.resolveResult(ctx);
    assertThat(result.status()).isEqualTo(ParticipantResolutionStatus.FAILED);
  }
}
