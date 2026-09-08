package com.fpt.workflow.shared.domain.value;

import java.util.Objects;

/** Assignability rules for bindings, expressions, variables, forms, resolvers, and connectors. */
public final class TypeCompatibility {

  private TypeCompatibility() {}

  public static boolean isAssignable(TypeDescriptor source, TypeDescriptor target) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    if (source.nullable() && !target.nullable()) {
      return false;
    }

    if (source.isCollection() || target.isCollection()) {
      if (!source.isCollection() || !target.isCollection()) {
        return false;
      }
      return isAssignable(source.itemType(), target.itemType());
    }

    return source.type() == target.type()
        || (source.type() == CanonicalValueType.INTEGER
            && target.type() == CanonicalValueType.NUMBER);
  }
}
