package com.fpt.workflow.operations.retention;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetentionActionRepository extends JpaRepository<RetentionAction, UUID> {
  List<RetentionAction> findAllByAggregateIdOrderByDecidedAtAsc(UUID aggregateId);
}
