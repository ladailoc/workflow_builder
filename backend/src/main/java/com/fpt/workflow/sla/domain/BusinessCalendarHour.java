package com.fpt.workflow.sla.domain;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "business_calendar_hours")
public class BusinessCalendarHour {
  @Id private UUID id;

  @Column(name = "calendar_id", nullable = false)
  private UUID calendarId;

  @Column(name = "day_of_week", nullable = false)
  private int dayOfWeek;

  @Column(name = "start_time", nullable = false)
  private LocalTime startTime;

  @Column(name = "end_time", nullable = false)
  private LocalTime endTime;

  protected BusinessCalendarHour() {}

  public static BusinessCalendarHour create(
      UUID id, UUID calendarId, DayOfWeek day, LocalTime start, LocalTime end) {
    if (!start.isBefore(end))
      throw new IllegalArgumentException("Working-hour start must precede end");
    BusinessCalendarHour h = new BusinessCalendarHour();
    h.id = id;
    h.calendarId = calendarId;
    h.dayOfWeek = day.getValue();
    h.startTime = start;
    h.endTime = end;
    return h;
  }

  public int getDayOfWeek() {
    return dayOfWeek;
  }

  public LocalTime getStartTime() {
    return startTime;
  }

  public LocalTime getEndTime() {
    return endTime;
  }
}
