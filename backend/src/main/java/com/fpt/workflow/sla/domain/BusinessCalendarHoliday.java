package com.fpt.workflow.sla.domain;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "business_calendar_holidays")
public class BusinessCalendarHoliday {
  @Id private UUID id;

  @Column(name = "calendar_id", nullable = false)
  private UUID calendarId;

  @Column(name = "holiday_date", nullable = false)
  private LocalDate holidayDate;

  @Column(nullable = false)
  private String name;

  @Column(name = "working_override", nullable = false)
  private boolean workingOverride;

  protected BusinessCalendarHoliday() {}

  public static BusinessCalendarHoliday create(
      UUID id, UUID calendarId, LocalDate date, String name, boolean override) {
    BusinessCalendarHoliday h = new BusinessCalendarHoliday();
    h.id = id;
    h.calendarId = calendarId;
    h.holidayDate = date;
    h.name = name;
    h.workingOverride = override;
    return h;
  }

  public LocalDate getHolidayDate() {
    return holidayDate;
  }

  public boolean isWorkingOverride() {
    return workingOverride;
  }
}
