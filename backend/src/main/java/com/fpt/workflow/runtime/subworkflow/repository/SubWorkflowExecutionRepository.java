package com.fpt.workflow.runtime.subworkflow.repository;

import com.fpt.workflow.runtime.subworkflow.domain.SubWorkflowExecution;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubWorkflowExecutionRepository extends JpaRepository<SubWorkflowExecution, UUID> {

  Optional<SubWorkflowExecution> findByParentNodeExecutionId(UUID parentNodeExecutionId);

  Optional<SubWorkflowExecution> findByChildEventId(UUID childEventId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT s FROM SubWorkflowExecution s WHERE s.parentNodeExecutionId = :parentNodeExecutionId")
  Optional<SubWorkflowExecution> findByParentNodeExecutionIdForUpdate(
      @Param("parentNodeExecutionId") UUID parentNodeExecutionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM SubWorkflowExecution s WHERE s.childEventId = :childEventId")
  Optional<SubWorkflowExecution> findByChildEventIdForUpdate(
      @Param("childEventId") UUID childEventId);
}
