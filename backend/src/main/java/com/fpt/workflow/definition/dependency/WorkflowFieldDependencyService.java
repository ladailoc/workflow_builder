package com.fpt.workflow.definition.dependency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.definition.repository.EdgeDefinitionRepository;
import com.fpt.workflow.definition.repository.NodeDefinitionRepository;
import com.fpt.workflow.definition.repository.WorkflowVariableRepository;
import com.fpt.workflow.form.domain.WorkflowForm;
import com.fpt.workflow.form.repository.WorkflowFormRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowFieldDependencyService {

  private final FieldDependencyAnalyzer analyzer;
  private final NodeDefinitionRepository nodeRepository;
  private final EdgeDefinitionRepository edgeRepository;
  private final WorkflowFormRepository formRepository;
  private final WorkflowVariableRepository variableRepository;
  private final ObjectMapper objectMapper;

  public WorkflowFieldDependencyService(
      FieldDependencyAnalyzer analyzer,
      NodeDefinitionRepository nodeRepository,
      EdgeDefinitionRepository edgeRepository,
      WorkflowFormRepository formRepository,
      WorkflowVariableRepository variableRepository,
      ObjectMapper objectMapper) {
    this.analyzer = analyzer;
    this.nodeRepository = nodeRepository;
    this.edgeRepository = edgeRepository;
    this.formRepository = formRepository;
    this.variableRepository = variableRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public DependencyReport analyze(FieldChange change) {
    List<DependencyResource> resources = new ArrayList<>();
    for (NodeDefinition node :
        nodeRepository.findAllByWorkflowVersionIdOrderByNodeKeyAsc(change.workflowVersionId())) {
      add(resources, DependencyResourceType.NODE, node.getId(), "/config", node.getConfigJson());
      add(
          resources,
          DependencyResourceType.NODE,
          node.getId(),
          "/inputSchema",
          node.getInputSchemaJson());
      add(
          resources,
          DependencyResourceType.NODE,
          node.getId(),
          "/outputSchema",
          node.getOutputSchemaJson());
    }
    for (EdgeDefinition edge :
        edgeRepository.findAllByWorkflowVersionIdOrderByPriorityAscIdAsc(
            change.workflowVersionId())) {
      add(
          resources,
          DependencyResourceType.EDGE,
          edge.getId(),
          "/condition",
          edge.getConditionJson());
      add(resources, DependencyResourceType.EDGE, edge.getId(), "/config", edge.getConfigJson());
    }
    for (WorkflowForm form :
        formRepository.findAllByWorkflowVersionIdOrderByFormKeyAsc(change.workflowVersionId())) {
      add(resources, DependencyResourceType.FORM, form.getId(), "/schema", form.getSchemaJson());
    }
    for (WorkflowVariable variable :
        variableRepository.findAllByWorkflowVersionIdOrderByKeyAsc(change.workflowVersionId())) {
      add(
          resources,
          DependencyResourceType.VARIABLE,
          variable.getId(),
          "/default",
          variable.getDefaultJson());
      add(
          resources,
          DependencyResourceType.VARIABLE,
          variable.getId(),
          "/type",
          objectMapper.valueToTree(variable.getType()));
    }
    return analyzer.analyze(change, resources);
  }

  private void add(
      List<DependencyResource> resources,
      DependencyResourceType type,
      java.util.UUID id,
      String path,
      com.fasterxml.jackson.databind.JsonNode content) {
    if (content != null) {
      resources.add(new DependencyResource(type, id, path, content));
    }
  }
}
