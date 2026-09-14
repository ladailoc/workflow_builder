package com.fpt.workflow.definition.repository;
import com.fpt.workflow.definition.domain.WorkflowStateDefinition;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface WorkflowStateDefinitionRepository extends JpaRepository<WorkflowStateDefinition,UUID>{ List<WorkflowStateDefinition> findAllByWorkflowVersionIdOrderByDisplayOrderAsc(UUID versionId); void deleteAllByWorkflowVersionId(UUID versionId); }
