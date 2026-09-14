package com.fpt.workflow.runtime.context;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface EventWorkflowInputRevisionRepository extends JpaRepository<EventWorkflowInputRevision,UUID>{
  List<EventWorkflowInputRevision> findAllByEventIdOrderByInputRevisionAsc(UUID eventId);
  Optional<EventWorkflowInputRevision> findFirstByEventIdOrderByInputRevisionDesc(UUID eventId);
  @Query("select coalesce(max(r.inputRevision),0) from EventWorkflowInputRevision r where r.eventId = :eventId")
  long maxInputRevision(@Param("eventId") UUID eventId);
}
