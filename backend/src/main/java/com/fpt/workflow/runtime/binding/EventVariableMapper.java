package com.fpt.workflow.runtime.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.domain.WorkflowVariable;
import com.fpt.workflow.resolver.expression.CompiledExpression;
import com.fpt.workflow.resolver.expression.ExpressionEngine;
import com.fpt.workflow.resolver.expression.ExpressionScope;
import com.fpt.workflow.resolver.expression.NullPolicy;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.runtime.domain.Event;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.shared.domain.value.TypeCompatibility;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** The only runtime write path for declared Event variables. Ticket data is never mutated here. */
@Component
public final class EventVariableMapper {

  private final ExpressionEngine expressionEngine;

  public EventVariableMapper(ExpressionEngine expressionEngine) {
    this.expressionEngine = expressionEngine;
  }

  public ObjectNode apply(
      Event event,
      List<WorkflowVariable> declarations,
      List<VariableMapping> mappings,
      EventContext context) {
    Map<String, WorkflowVariable> byKey = new HashMap<>();
    declarations.forEach(variable -> byKey.put(variable.getKey(), variable));
    Set<String> mapped = new HashSet<>();
    ObjectNode changes = JsonNodeFactory.instance.objectNode();
    for (VariableMapping mapping : List.copyOf(mappings)) {
      if (!mapped.add(mapping.variableKey())) {
        throw new BindingException(
            "VARIABLE_MAPPING.DUPLICATE_TARGET",
            mapping.variableKey(),
            "Variable mapping target is duplicated");
      }
      WorkflowVariable declaration = byKey.get(mapping.variableKey());
      if (declaration == null) {
        throw new BindingException(
            "VARIABLE_MAPPING.UNDECLARED_VARIABLE",
            mapping.variableKey(),
            "Variable is not declared by the bound WorkflowVersion");
      }
      if (!declaration.isMutable()) {
        throw new BindingException(
            "VARIABLE_MAPPING.IMMUTABLE_VARIABLE", mapping.variableKey(), "Variable is immutable");
      }
      CompiledExpression compiled =
          expressionEngine.compile(
              mapping.expression(), ExpressionScope.RUNTIME, context.expressionSchema());
      if (!TypeCompatibility.isAssignable(compiled.resultType(), declaration.getType())) {
        throw new BindingException(
            "VARIABLE_MAPPING.TYPE_MISMATCH",
            mapping.variableKey(),
            compiled.resultType().displayName()
                + " is not assignable to "
                + declaration.getType().displayName());
      }
      JsonNode value =
          expressionEngine.evaluate(compiled, context.value(), NullPolicy.NULL_IS_FALSE);
      if (value == null || value.isMissingNode()) value = JsonNodeFactory.instance.nullNode();
      CanonicalValueValidator.requireValid(declaration.getType(), value);
      changes.set(mapping.variableKey(), value.deepCopy());
    }
    event.applyDeclaredVariableMappings(changes);
    return changes.deepCopy();
  }
}
