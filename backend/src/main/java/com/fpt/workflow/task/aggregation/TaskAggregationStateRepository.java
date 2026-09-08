package com.fpt.workflow.task.aggregation;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TaskAggregationStateRepository extends JpaRepository<TaskAggregationState, UUID> {
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from TaskAggregationState s where s.nodeExecutionId=:id")
  Optional<TaskAggregationState> findByIdForUpdate(@Param("id") UUID id);
}
