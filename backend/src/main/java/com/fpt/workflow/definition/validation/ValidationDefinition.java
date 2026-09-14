package com.fpt.workflow.definition.validation;

import com.fpt.workflow.definition.domain.EdgeDefinition;
import com.fpt.workflow.definition.domain.NodeDefinition;
import com.fpt.workflow.definition.domain.WorkflowInputDefinition;
import com.fpt.workflow.definition.domain.WorkflowStateDefinition;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.definition.domain.WorkflowVersion;
import com.fpt.workflow.form.domain.WorkflowForm;
import java.util.List;
import java.util.Objects;

public record ValidationDefinition(
    WorkflowVersion version,
    List<NodeDefinition> nodes,
    List<EdgeDefinition> edges,
    List<WorkflowForm> forms,
    List<WorkflowVariable> variables,
    List<WorkflowInputDefinition> inputs,
    List<WorkflowStateDefinition> states) {

  public ValidationDefinition {
    Objects.requireNonNull(version, "version");
    nodes = List.copyOf(nodes);
    edges = List.copyOf(edges);
    forms = List.copyOf(forms);
    variables = List.copyOf(variables);
    inputs = List.copyOf(inputs);
    states = List.copyOf(states);
  }

  /** v2.4 compatible constructor: legacy compiles carry no typed input/state contract rows. */
  public ValidationDefinition(
      WorkflowVersion version,
      List<NodeDefinition> nodes,
      List<EdgeDefinition> edges,
      List<WorkflowForm> forms,
      List<WorkflowVariable> variables) {
    this(version, nodes, edges, forms, variables, List.of(), List.of());
  }
}
