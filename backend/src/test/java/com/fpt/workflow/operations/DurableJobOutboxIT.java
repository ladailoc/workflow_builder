package com.fpt.workflow.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.operations.job.*;
import com.fpt.workflow.operations.outbox.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DurableJobOutboxIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> postgresls = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired WorkflowJobTransactions jobTransactions;
  @Autowired WorkflowJobRepository jobs;
  @Autowired OutboxTransactions outboxTransactions;
  @Autowired OutboxEventRepository outbox;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void duplicateDedupKeyReturnsOriginalJob() {
    String key = "job:" + UUID.randomUUID();
    WorkflowJob first =
        jobTransactions.enqueue(
            "TEST",
            "EVENT",
            UUID.randomUUID(),
            mapper.createObjectNode(),
            3,
            java.time.Instant.now(),
            key);
    WorkflowJob duplicate =
        jobTransactions.enqueue(
            "TEST",
            "EVENT",
            UUID.randomUUID(),
            mapper.createObjectNode(),
            9,
            java.time.Instant.now(),
            key);
    assertThat(duplicate.getId()).isEqualTo(first.getId());
    assertThat(jobs.findByDedupKey(key)).isPresent();
  }

  @Test
  void crashedWorkerLeaseExpiresAndAnotherWorkerReclaims() {
    WorkflowJob job = enqueueJob("crash");
    assertThat(jobTransactions.claim("worker-a", 1, Duration.ofMinutes(5)))
        .extracting(WorkflowJob::getId)
        .containsExactly(job.getId());
    jdbc.update(
        "UPDATE workflow_jobs SET lease_until=now()-interval '1 second' WHERE id=?", job.getId());
    List<WorkflowJob> reclaimed = jobTransactions.claim("worker-b", 1, Duration.ofMinutes(5));
    assertThat(reclaimed).extracting(WorkflowJob::getId).containsExactly(job.getId());
    assertThat(reclaimed.getFirst().getAttempts()).isEqualTo(2);
    assertThat(jobTransactions.complete(job.getId(), "worker-a")).isFalse();
    assertThat(jobTransactions.complete(job.getId(), "worker-b")).isTrue();
  }

  @Test
  void twoWorkersUsingSkipLockedNeverClaimSameJob() throws Exception {
    List<UUID> expected = new ArrayList<>();
    for (int i = 0; i < 12; i++) expected.add(enqueueJob("race-" + i).getId());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<List<WorkflowJob>> left =
          pool.submit(
              () -> {
                start.await();
                return jobTransactions.claim("left", 12, Duration.ofMinutes(1));
              });
      Future<List<WorkflowJob>> right =
          pool.submit(
              () -> {
                start.await();
                return jobTransactions.claim("right", 12, Duration.ofMinutes(1));
              });
      start.countDown();
      List<UUID> a = left.get(10, TimeUnit.SECONDS).stream().map(WorkflowJob::getId).toList();
      List<UUID> b = right.get(10, TimeUnit.SECONDS).stream().map(WorkflowJob::getId).toList();
      assertThat(Collections.disjoint(a, b)).isTrue();
      assertThat(new HashSet<>(concat(a, b))).containsAll(expected);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void repeatedFailureBecomesOperationallyVisibleDeadJob() {
    WorkflowJob job =
        jobTransactions.enqueue(
            "TEST",
            "EVENT",
            UUID.randomUUID(),
            mapper.createObjectNode(),
            2,
            java.time.Instant.now(),
            "dead:" + UUID.randomUUID());
    jobTransactions.claim("worker", 1, Duration.ofMinutes(1));
    jobTransactions.fail(
        job.getId(), "worker", mapper.createObjectNode().put("code", "TEMP"), Duration.ZERO, false);
    jobTransactions.claim("worker", 1, Duration.ofMinutes(1));
    jobTransactions.fail(
        job.getId(), "worker", mapper.createObjectNode().put("code", "TEMP"), Duration.ZERO, false);
    assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
        .isEqualTo(WorkflowJobStatus.DEAD);
    assertThat(jobs.findAllByStatusOrderByUpdatedAtAsc(WorkflowJobStatus.DEAD))
        .extracting(WorkflowJob::getId)
        .contains(job.getId());
  }

  @Test
  void operatorRetryPreservesAttemptHistoryAndGrantsOneAdditionalAttempt() {
    WorkflowJob job =
        jobTransactions.enqueue(
            "TEST",
            "EVENT",
            UUID.randomUUID(),
            mapper.createObjectNode(),
            1,
            java.time.Instant.now(),
            "operator-retry:" + UUID.randomUUID());
    WorkflowJob running = jobTransactions.claim("worker", 1, Duration.ofMinutes(1)).getFirst();
    jobTransactions.fail(
        running.getId(),
        "worker",
        mapper.createObjectNode().put("code", "PERMANENT"),
        Duration.ZERO,
        true);

    WorkflowJob dead = jobs.findById(job.getId()).orElseThrow();
    assertThat(jobTransactions.retryDead(dead.getId(), dead.getLockVersion())).isTrue();

    WorkflowJob retried = jobs.findById(job.getId()).orElseThrow();
    assertThat(retried.getStatus()).isEqualTo(WorkflowJobStatus.RETRY);
    assertThat(retried.getAttempts()).isEqualTo(1);
    assertThat(retried.getMaxAttempts()).isEqualTo(2);
    assertThat(jobTransactions.claim("recovery-worker", 1, Duration.ofMinutes(1)))
        .extracting(WorkflowJob::getId)
        .containsExactly(job.getId());
    assertThat(jobTransactions.retryDead(dead.getId(), dead.getLockVersion())).isFalse();
  }

  @Test
  void outboxPublisherCrashIsReclaimableAndDeduplicated() {
    String key = "outbox:" + UUID.randomUUID();
    OutboxEvent first =
        outboxTransactions.enqueue(
            "TICKET_CHANGED",
            "TICKET",
            UUID.randomUUID(),
            mapper.createObjectNode().put("safe", true),
            3,
            key);
    OutboxEvent duplicate =
        outboxTransactions.enqueue(
            "TICKET_CHANGED", "TICKET", UUID.randomUUID(), mapper.createObjectNode(), 3, key);
    assertThat(duplicate.getId()).isEqualTo(first.getId());
    assertThat(outboxTransactions.claim("publisher-crashed", 1, Duration.ofMinutes(1))).hasSize(1);
    jdbc.update(
        "UPDATE outbox_events SET lease_until=now()-interval '1 second' WHERE id=?", first.getId());
    assertThat(outboxTransactions.claim("publisher-retry", 1, Duration.ofMinutes(1))).hasSize(1);
    assertThat(outboxTransactions.published(first.getId(), "publisher-retry")).isTrue();
    OutboxEvent published = outbox.findById(first.getId()).orElseThrow();
    assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    assertThat(published.getAttempts()).isEqualTo(2);
  }

  @Test
  void rolledBackBusinessTransactionDoesNotCommitOutboxRecord() {
    String key = "rollback:" + UUID.randomUUID();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      outboxTransactions.enqueue(
                          "BUSINESS_CHANGED",
                          "TICKET",
                          UUID.randomUUID(),
                          mapper.createObjectNode().put("committed", false),
                          3,
                          key);
                      throw new IllegalStateException("force rollback after business change");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(outbox.findByDedupKey(key)).isEmpty();
  }

  @Test
  void transientTransportFailureRetriesThenPublishesDurably() {
    String key = "retry:" + UUID.randomUUID();
    OutboxEvent event =
        outboxTransactions.enqueue(
            "TICKET_SUBMITTED",
            "TICKET",
            UUID.randomUUID(),
            mapper.createObjectNode(),
            3,
            key);
    AtomicInteger calls = new AtomicInteger();
    OutboxPublisher publisher =
        new OutboxPublisher(
            outboxTransactions,
            message -> {
              if (calls.getAndIncrement() == 0) throw new IllegalStateException("temporary");
            });

    assertThat(publisher.publishAvailable("publisher-a", 1, Duration.ofMinutes(1), Duration.ZERO))
        .isEqualTo(1);
    assertThat(outbox.findById(event.getId()).orElseThrow().getStatus())
        .isEqualTo(OutboxStatus.RETRY);

    assertThat(publisher.publishAvailable("publisher-b", 1, Duration.ofMinutes(1), Duration.ZERO))
        .isEqualTo(1);
    OutboxEvent published = outbox.findById(event.getId()).orElseThrow();
    assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    assertThat(published.getAttempts()).isEqualTo(2);
    assertThat(calls).hasValue(2);
  }

  @Test
  void twoOutboxPublishersDoNotDeliverSameClaimConcurrently() throws Exception {
    OutboxEvent event =
        outboxTransactions.enqueue(
            "TICKET_SUBMITTED",
            "TICKET",
            UUID.randomUUID(),
            mapper.createObjectNode(),
            3,
            "publisher-race:" + UUID.randomUUID());
    AtomicInteger deliveries = new AtomicInteger();
    OutboxTransport transport = ignored -> deliveries.incrementAndGet();
    OutboxPublisher publisher = new OutboxPublisher(outboxTransactions, transport);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<Integer> left =
          pool.submit(
              () -> {
                start.await();
                return publisher.publishAvailable(
                    "publisher-left", 1, Duration.ofMinutes(1), Duration.ZERO);
              });
      Future<Integer> right =
          pool.submit(
              () -> {
                start.await();
                return publisher.publishAvailable(
                    "publisher-right", 1, Duration.ofMinutes(1), Duration.ZERO);
              });
      start.countDown();

      assertThat(left.get(10, TimeUnit.SECONDS) + right.get(10, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(deliveries).hasValue(1);
      assertThat(outbox.findById(event.getId()).orElseThrow().getStatus())
          .isEqualTo(OutboxStatus.PUBLISHED);
    } finally {
      pool.shutdownNow();
    }
  }

  private WorkflowJob enqueueJob(String suffix) {
    return jobTransactions.enqueue(
        "TEST",
        "EVENT",
        UUID.randomUUID(),
        mapper.createObjectNode(),
        3,
        java.time.Instant.now(),
        suffix + ":" + UUID.randomUUID());
  }

  private static <T> List<T> concat(List<T> a, List<T> b) {
    List<T> values = new ArrayList<>(a);
    values.addAll(b);
    return values;
  }
}
