package com.fpt.workflow.sla.repository;

import com.fpt.workflow.sla.domain.BusinessCalendarHour;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessCalendarHourRepository extends JpaRepository<BusinessCalendarHour, UUID> {
  List<BusinessCalendarHour> findAllByCalendarId(UUID id);
}
