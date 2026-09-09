package com.fpt.workflow.operations.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RetentionPolicyPersistenceIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("workflow_retention_test")
          .withUsername("workflow_test")
          .withPassword("workflow_test");

  @Autowired private RetentionPolicyRepository policyRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void persistsAndEnforcesUniqueCategoryPolicy() {
    Instant now = Instant.now();
    RetentionPolicy policy =
        RetentionPolicy.create(
            UUID.randomUUID(), RetentionCategory.AUDIT, 365, RetentionExpiryAction.MASK, true, now);
    policyRepository.saveAndFlush(policy);

    var found = policyRepository.findByCategoryAndEnabledTrue(RetentionCategory.AUDIT);
    assertThat(found).isPresent();
    assertThat(found.get().getRetentionDays()).isEqualTo(365);
    assertThat(found.get().getExpiryAction()).isEqualTo(RetentionExpiryAction.MASK);

    // Duplicate category must be rejected by uq_retention_policy_category
    RetentionPolicy duplicate =
        RetentionPolicy.create(
            UUID.randomUUID(),
            RetentionCategory.AUDIT,
            180,
            RetentionExpiryAction.ANONYMIZE,
            true,
            now);
    assertThatThrownBy(() -> policyRepository.saveAndFlush(duplicate))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void recordsRetentionActionAndEnforcesAppendOnlyTrigger() {
    Instant now = Instant.now();
    RetentionPolicy policy =
        policyRepository.saveAndFlush(
            RetentionPolicy.create(
                UUID.randomUUID(),
                RetentionCategory.NOTIFICATION,
                30,
                RetentionExpiryAction.HARD_DELETE,
                true,
                now));

    UUID actionId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO retention_actions (id, policy_id, category, aggregate_type, aggregate_id, action, reason, decided_at) "
            + "VALUES (?, ?, 'NOTIFICATION', 'NOTIFICATION_DISPATCH', ?, 'HARD_DELETE', 'Retention window expired', now())",
        actionId,
        policy.getId(),
        aggregateId);

    // Verify record exists
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM retention_actions WHERE id = ?", Integer.class, actionId);
    assertThat(count).isEqualTo(1);

    // Attempting to UPDATE retention_actions must fail via trigger
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE retention_actions SET reason = 'Modified' WHERE id = ?", actionId))
        .isInstanceOf(DataAccessException.class);

    // Attempting to DELETE retention_actions must fail via trigger
    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM retention_actions WHERE id = ?", actionId))
        .isInstanceOf(DataAccessException.class);
  }
}
