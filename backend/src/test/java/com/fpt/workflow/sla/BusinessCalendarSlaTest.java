package com.fpt.workflow.sla;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.sla.domain.*;
import com.fpt.workflow.sla.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BusinessCalendarSlaTest {
  private final BusinessTimeCalculator calculator = new BusinessTimeCalculator();

  @Test
  void skipsWeekendAndRespectsWorkingHoursInHoChiMinh() {
    BusinessCalendar c = calendar(ZoneId.of("Asia/Ho_Chi_Minh"));
    List<BusinessCalendarHour> hours = weekdays(c);
    Instant friday = ZonedDateTime.of(2026, 9, 11, 16, 0, 0, 0, c.getZoneId()).toInstant();
    Instant due = calculator.addWorkingTime(friday, Duration.ofHours(2), c, hours, List.of());
    assertThat(due.atZone(c.getZoneId()).toLocalDateTime())
        .isEqualTo(LocalDateTime.of(2026, 9, 14, 10, 0));
  }

  @Test
  void skipsHoliday() {
    BusinessCalendar c = calendar(ZoneId.of("Asia/Ho_Chi_Minh"));
    UUID id = c.getId();
    Instant friday = ZonedDateTime.of(2026, 9, 11, 16, 0, 0, 0, c.getZoneId()).toInstant();
    Instant due =
        calculator.addWorkingTime(
            friday,
            Duration.ofHours(2),
            c,
            weekdays(c),
            List.of(
                BusinessCalendarHoliday.create(
                    UUID.randomUUID(), id, LocalDate.of(2026, 9, 14), "holiday", false)));
    assertThat(due.atZone(c.getZoneId()).toLocalDateTime())
        .isEqualTo(LocalDateTime.of(2026, 9, 15, 10, 0));
  }

  @Test
  void usesInstantArithmeticAcrossDstGap() {
    BusinessCalendar c = calendar(ZoneId.of("America/New_York"));
    BusinessCalendarHour sunday =
        BusinessCalendarHour.create(
            UUID.randomUUID(), c.getId(), DayOfWeek.SUNDAY, LocalTime.of(1, 0), LocalTime.of(4, 0));
    Instant start = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, c.getZoneId()).toInstant();
    Instant due =
        calculator.addWorkingTime(start, Duration.ofHours(2), c, List.of(sunday), List.of());
    assertThat(due.atZone(c.getZoneId()).toLocalTime()).isEqualTo(LocalTime.of(4, 0));
    assertThat(Duration.between(start, due)).isEqualTo(Duration.ofHours(2));
  }

  @Test
  void dueAtIsSnapshotAndTimeoutRaceHasOneWinner() throws Exception {
    Instant started = Instant.parse("2026-09-08T00:00:00Z"), due = started.plusSeconds(3600);
    var config = JsonNodeFactory.instance.objectNode().put("durationMinutes", 60);
    SlaExecution sla =
        SlaExecution.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            config,
            started,
            due,
            due);
    config.put("durationMinutes", 1);
    assertThat(sla.getDueAt()).isEqualTo(due);
    assertThat(sla.getConfigSnapshotJson().path("durationMinutes").asInt()).isEqualTo(60);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<Boolean>> attempts = new ArrayList<>();
      for (int i = 0; i < 20; i++) attempts.add(pool.submit(() -> sla.breach(due)));
      long winners = 0;
      for (var attempt : attempts) if (attempt.get()) winners++;
      assertThat(winners).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  private BusinessCalendar calendar(ZoneId zone) {
    return BusinessCalendar.create(
        UUID.randomUUID(), "CAL_" + UUID.randomUUID(), "Calendar", zone, Instant.EPOCH);
  }

  private List<BusinessCalendarHour> weekdays(BusinessCalendar c) {
    List<BusinessCalendarHour> result = new ArrayList<>();
    for (int day = 1; day <= 5; day++)
      result.add(
          BusinessCalendarHour.create(
              UUID.randomUUID(),
              c.getId(),
              DayOfWeek.of(day),
              LocalTime.of(9, 0),
              LocalTime.of(17, 0)));
    return result;
  }
}
