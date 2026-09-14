package com.fpt.workflow.definition.repository;

import com.fpt.workflow.definition.domain.WorkflowDefinition;
import com.fpt.workflow.shared.domain.lifecycle.WorkflowDefinitionLifecycle;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

  Optional<WorkflowDefinition> findByKey(String key);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select definition from WorkflowDefinition definition where definition.id = :id")
  Optional<WorkflowDefinition> findByIdForUpdate(@Param("id") UUID id);

  boolean existsByKey(String key);

  Page<WorkflowDefinition> findAllByLifecycle(
      WorkflowDefinitionLifecycle lifecycle, Pageable pageable);

  @Query(
      "select definition from WorkflowDefinition definition "
          + "where lower(definition.name) like :pattern or lower(definition.key) like :pattern")
  Page<WorkflowDefinition> searchByPattern(@Param("pattern") String pattern, Pageable pageable);

  @Query(
      "select definition from WorkflowDefinition definition "
          + "where (lower(definition.name) like :pattern or lower(definition.key) like :pattern) "
          + "and definition.lifecycle = :lifecycle")
  Page<WorkflowDefinition> searchByPatternAndLifecycle(
      @Param("pattern") String pattern,
      @Param("lifecycle") WorkflowDefinitionLifecycle lifecycle,
      Pageable pageable);
}
