package com.fpt.workflow.nodetype;

import java.util.List;

@FunctionalInterface
public interface NodeValidator {
  List<NodeValidationIssue> validate(NodeValidationContext context);
}
