package com.fpt.workflow.definition.repository;
import com.fpt.workflow.definition.domain.WorkflowInputDefinition;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface WorkflowInputDefinitionRepository extends JpaRepository<WorkflowInputDefinition,UUID>{ List<WorkflowInputDefinition> findAllByWorkflowVersionIdOrderByOrdinalAsc(UUID versionId); Optional<WorkflowInputDefinition> findByWorkflowVersionIdAndInputKey(UUID versionId,String key); void deleteAllByWorkflowVersionId(UUID versionId); }
