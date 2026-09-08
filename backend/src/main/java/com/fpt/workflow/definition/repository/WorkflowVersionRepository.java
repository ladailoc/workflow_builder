package com.fpt.workflow.definition.repository;

import com.fpt.workflow.definition.domain.WorkflowVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, UUID> {

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select workflowVersion from WorkflowVersion workflowVersion where workflowVersion.id = :id")
  Optional<WorkflowVersion> findByIdForUpdate(@Param("id") UUID id);

  Optional<WorkflowVersion> findByDefinitionIdAndStatus(
      UUID definitionId, com.fpt.workflow.shared.domain.lifecycle.WorkflowVersionStatus status);

  Optional<WorkflowVersion> findByDefinitionIdAndVersionNo(UUID definitionId, int versionNo);

  @Query(
      "select coalesce(max(workflowVersion.versionNo), 0) from WorkflowVersion workflowVersion "
          + "where workflowVersion.definitionId = :definitionId")
  int findMaxVersionNoByDefinitionId(@Param("definitionId") UUID definitionId);

  Page<WorkflowVersion> findAllByDefinitionId(UUID definitionId, Pageable pageable);
}
