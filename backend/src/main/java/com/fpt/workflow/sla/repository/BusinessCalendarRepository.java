package com.fpt.workflow.sla.repository;

import com.fpt.workflow.sla.domain.BusinessCalendar;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessCalendarRepository extends JpaRepository<BusinessCalendar, UUID> {
  Optional<BusinessCalendar> findByKey(String key);
}
