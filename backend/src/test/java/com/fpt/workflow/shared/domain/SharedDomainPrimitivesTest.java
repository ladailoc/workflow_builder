package com.fpt.workflow.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.shared.domain.page.PageRequest;
import com.fpt.workflow.shared.domain.page.PageResult;
import com.fpt.workflow.shared.domain.page.SortDirection;
import com.fpt.workflow.shared.domain.page.SortOrder;
import com.fpt.workflow.testing.FixedUuidGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedDomainPrimitivesTest {

  private static final UUID VALUE = UUID.fromString("10000000-0000-4000-8000-000000000001");

  @Test
  void providesDistinctUuidBackedIdentifierTypes() {
    assertThat(AggregateId.parse(VALUE.toString()).value()).isEqualTo(VALUE);
    assertThat(CommandId.generate(new FixedUuidGenerator(VALUE)).value()).isEqualTo(VALUE);
    assertThat(CorrelationId.parse(VALUE.toString()).toString()).isEqualTo(VALUE.toString());
    assertThatThrownBy(() -> new AggregateId(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void serializesUuidValuesAsCanonicalJsonStrings() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    CommandId commandId = new CommandId(VALUE);

    assertThat(objectMapper.writeValueAsString(commandId)).isEqualTo("\"" + VALUE + "\"");
    assertThat(objectMapper.readValue("\"" + VALUE + "\"", CommandId.class)).isEqualTo(commandId);
  }

  @Test
  void enforcesMonotonicVersionsAndExpectedVersionMatches() {
    AggregateVersion initial = AggregateVersion.initial();
    AggregateVersion next = initial.next();

    assertThat(initial.value()).isZero();
    assertThat(next.value()).isEqualTo(1);
    OptimisticVersionGuard.requireMatch(next, new ExpectedVersion(1));
    assertThatThrownBy(() -> OptimisticVersionGuard.requireMatch(next, new ExpectedVersion(0)))
        .isInstanceOf(StaleAggregateVersionException.class)
        .hasMessageContaining("Expected aggregate version 0")
        .hasMessageContaining("current version is 1");
  }

  @Test
  void usesInstantAndRejectsBackwardsLifecycleTimestamps() {
    Instant createdAt = Instant.parse("2026-09-07T03:00:00Z");
    LifecycleTimestamps timestamps = LifecycleTimestamps.createdAt(createdAt);
    Instant updatedAt = createdAt.plusSeconds(1);

    assertThat(timestamps.updatedAt(updatedAt).updatedAt()).isEqualTo(updatedAt);
    assertThatThrownBy(() -> timestamps.updatedAt(createdAt.minusNanos(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesPaginationAndSortingContracts() {
    PageRequest request =
        new PageRequest(1, 2, List.of(new SortOrder("createdAt", SortDirection.DESC)));
    PageResult<String> result = new PageResult<>(List.of("c", "d"), 1, 2, 5);

    assertThat(request.sort()).containsExactly(SortOrder.descending("createdAt"));
    assertThat(result.totalPages()).isEqualTo(3);
    assertThat(result.hasNext()).isTrue();
    assertThatThrownBy(() -> PageRequest.of(0, PageRequest.MAX_SIZE + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SortOrder.ascending("createdAt desc; drop table"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
