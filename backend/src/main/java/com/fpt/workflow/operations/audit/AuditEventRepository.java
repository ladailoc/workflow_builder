package com.fpt.workflow.operations.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

  List<AuditEvent> findAllByAggregateTypeAndAggregateIdOrderByOccurredAtAsc(
      String aggregateType, UUID aggregateId);

  List<AuditEvent> findAllByCorrelationIdOrderByOccurredAtAsc(UUID correlationId);
}
