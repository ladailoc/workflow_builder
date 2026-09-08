package com.fpt.workflow.sla.repository;

import com.fpt.workflow.sla.domain.BusinessCalendarHoliday;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessCalendarHolidayRepository
    extends JpaRepository<BusinessCalendarHoliday, UUID> {
  List<BusinessCalendarHoliday> findAllByCalendarId(UUID id);
}
