package com.fpt.workflow.runtime.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.resolver.expression.CompiledExpression;
import com.fpt.workflow.resolver.expression.ExpressionEngine;
import com.fpt.workflow.resolver.expression.ExpressionScope;
import com.fpt.workflow.resolver.expression.NullPolicy;
import com.fpt.workflow.runtime.context.EventContext;
import com.fpt.workflow.shared.domain.value.CanonicalValueValidator;
import com.fpt.workflow.shared.domain.value.TypeCompatibility;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class InputBindingResolver {

  private final ExpressionEngine expressionEngine;

  public InputBindingResolver(ExpressionEngine expressionEngine) {
    this.expressionEngine = expressionEngine;
  }

  public ObjectNode resolve(List<InputBinding> bindings, EventContext context) {
    ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
    Set<String> targets = new HashSet<>();
    for (InputBinding binding : List.copyOf(bindings)) {
      if (!targets.add(binding.target())) {
        throw new BindingException(
            "INPUT_BINDING.DUPLICATE_TARGET", binding.target(), "Binding target is duplicated");
      }
      CompiledExpression compiled;
      try {
        compiled =
            expressionEngine.compile(
                binding.expression(), ExpressionScope.RUNTIME, context.expressionSchema());
      } catch (IllegalArgumentException exception) {
        throw new BindingException(
            "INPUT_BINDING.INVALID_EXPRESSION", binding.target(), exception.getMessage());
      }
      if (!TypeCompatibility.isAssignable(compiled.resultType(), binding.expectedType())) {
        throw new BindingException(
            "INPUT_BINDING.TYPE_MISMATCH",
            binding.target(),
            compiled.resultType().displayName()
                + " is not assignable to "
                + binding.expectedType().displayName());
      }
      JsonNode value =
          expressionEngine.evaluate(compiled, context.value(), NullPolicy.NULL_IS_FALSE);
      if (value == null || value.isMissingNode() || value.isNull()) {
        value = resolveMissing(binding);
      }
      if (binding.required() && value.isNull()) {
        throw new BindingException(
            "INPUT_BINDING.REQUIRED_VALUE_MISSING",
            binding.target(),
            "Required binding resolved to null");
      }
      try {
        CanonicalValueValidator.requireValid(binding.expectedType(), value);
      } catch (IllegalArgumentException exception) {
        throw new BindingException(
            "INPUT_BINDING.VALUE_TYPE_MISMATCH", binding.target(), exception.getMessage());
      }
      setTarget(snapshot, binding.target(), value);
    }
    return snapshot.deepCopy();
  }

  private JsonNode resolveMissing(InputBinding binding) {
    return switch (binding.onMissing()) {
      case ERROR ->
          throw new BindingException(
              "INPUT_BINDING.VALUE_MISSING",
              binding.target(),
              "Expression resolved to a missing or null value");
      case USE_DEFAULT -> binding.defaultValue().deepCopy();
      case NULL -> JsonNodeFactory.instance.nullNode();
    };
  }

  private void setTarget(ObjectNode root, String target, JsonNode value) {
    String[] segments = target.split("\\.");
    ObjectNode current = root;
    for (int index = 0; index < segments.length - 1; index++) {
      JsonNode existing = current.get(segments[index]);
      if (existing != null && !existing.isObject()) {
        throw new BindingException(
            "INPUT_BINDING.TARGET_CONFLICT", target, "Binding target conflicts with a scalar");
      }
      current = existing == null ? current.putObject(segments[index]) : (ObjectNode) existing;
    }
    current.set(segments[segments.length - 1], value.deepCopy());
  }
}
