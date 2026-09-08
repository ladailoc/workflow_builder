package com.fpt.workflow.sla.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.sla.domain.*;
import com.fpt.workflow.sla.repository.*;
import com.fpt.workflow.task.service.TaskSlaActivationPort;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SlaActivationService implements TaskSlaActivationPort {
  private final BusinessCalendarRepository calendars;
  private final BusinessCalendarHourRepository hours;
  private final BusinessCalendarHolidayRepository holidays;
  private final SlaExecutionRepository executions;
  private final BusinessTimeCalculator calculator;
  private final UuidGenerator uuids;

  public SlaActivationService(
      BusinessCalendarRepository calendars,
      BusinessCalendarHourRepository hours,
      BusinessCalendarHolidayRepository holidays,
      SlaExecutionRepository executions,
      BusinessTimeCalculator calculator,
      UuidGenerator uuids) {
    this.calendars = calendars;
    this.hours = hours;
    this.holidays = holidays;
    this.executions = executions;
    this.calculator = calculator;
    this.uuids = uuids;
  }

  public Optional<SlaPlan> plan(NodeDefinition node, Instant activatedAt) {
    JsonNode config = node.getConfigJson().path("sla");
    if (!config.isObject() || !config.hasNonNull("durationMinutes")) return Optional.empty();
    long minutes = config.path("durationMinutes").asLong();
    if (minutes <= 0) throw new IllegalArgumentException("SLA durationMinutes must be positive");
    UUID calendarId = null;
    Instant due;
    if (config.hasNonNull("businessCalendarId")) {
      calendarId = UUID.fromString(config.get("businessCalendarId").asText());
      BusinessCalendar calendar = calendars.findById(calendarId).orElseThrow();
      if (!"ACTIVE".equals(calendar.getStatus()))
        throw new IllegalStateException("Business calendar is inactive");
      due =
          calculator.addWorkingTime(
              activatedAt,
              Duration.ofMinutes(minutes),
              calendar,
              hours.findAllByCalendarId(calendarId),
              holidays.findAllByCalendarId(calendarId));
    } else due = activatedAt.plus(Duration.ofMinutes(minutes));
    long reminder = config.path("reminderMinutesBefore").asLong(0);
    Instant next = reminder > 0 ? due.minus(Duration.ofMinutes(reminder)) : due;
    return Optional.of(new SlaPlan(calendarId, config.deepCopy(), due, next));
  }

  public void record(
      UUID eventId, UUID nodeExecutionId, UUID taskId, Instant startedAt, SlaPlan plan) {
    executions.save(
        SlaExecution.start(
            uuids.generate(),
            eventId,
            nodeExecutionId,
            taskId,
            plan.calendarId(),
            plan.configSnapshot(),
            startedAt,
            plan.dueAt(),
            plan.nextActionAt()));
  }

  @Override
  public void complete(UUID taskId, Instant completedAt) {
    executions
        .findByTaskId(taskId)
        .ifPresent(
            sla -> {
              sla.complete(completedAt);
              executions.save(sla);
            });
  }

  @Override
  public void cancel(UUID taskId, Instant cancelledAt) {
    executions
        .findByTaskId(taskId)
        .ifPresent(
            sla -> {
              sla.complete(cancelledAt);
              executions.save(sla);
            });
  }
}
