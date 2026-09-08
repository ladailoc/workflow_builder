package com.fpt.workflow.sla.service;

import com.fpt.workflow.sla.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Adds elapsed working time in a calendar zone and always returns an unambiguous Instant. */
@Component
public class BusinessTimeCalculator {
  public Instant addWorkingTime(
      Instant start,
      Duration duration,
      BusinessCalendar calendar,
      List<BusinessCalendarHour> hours,
      List<BusinessCalendarHoliday> holidays) {
    if (duration.isNegative()) throw new IllegalArgumentException("duration cannot be negative");
    if (duration.isZero()) return start;
    ZoneId zone = calendar.getZoneId();
    ZonedDateTime cursor = start.atZone(zone);
    Duration remaining = duration;
    Map<LocalDate, Boolean> holidayMap = new HashMap<>();
    for (var h : holidays) holidayMap.put(h.getHolidayDate(), h.isWorkingOverride());
    Map<Integer, List<BusinessCalendarHour>> byDay = new HashMap<>();
    for (var h : hours)
      byDay.computeIfAbsent(h.getDayOfWeek(), ignored -> new ArrayList<>()).add(h);
    byDay
        .values()
        .forEach(list -> list.sort(Comparator.comparing(BusinessCalendarHour::getStartTime)));
    for (int guard = 0; guard < 36600; guard++) {
      LocalDate day = cursor.toLocalDate();
      Boolean override = holidayMap.get(day);
      List<BusinessCalendarHour> windows =
          Boolean.FALSE.equals(override)
              ? List.of()
              : byDay.getOrDefault(day.getDayOfWeek().getValue(), List.of());
      for (var window : windows) {
        ZonedDateTime windowStart = ZonedDateTime.of(day, window.getStartTime(), zone);
        ZonedDateTime windowEnd = ZonedDateTime.of(day, window.getEndTime(), zone);
        ZonedDateTime effective = cursor.isAfter(windowStart) ? cursor : windowStart;
        if (!effective.isBefore(windowEnd)) continue;
        Duration available = Duration.between(effective.toInstant(), windowEnd.toInstant());
        if (remaining.compareTo(available) <= 0) return effective.toInstant().plus(remaining);
        remaining = remaining.minus(available);
        cursor = windowEnd;
      }
      cursor = day.plusDays(1).atStartOfDay(zone);
    }
    throw new IllegalStateException("No working time found within calendar guard");
  }
}
