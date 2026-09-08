package com.fpt.workflow.resolver.expression;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Data-only expression AST. There is deliberately no raw source-code expression node. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = LiteralExpression.class, name = "LITERAL"),
  @JsonSubTypes.Type(value = ReferenceExpression.class, name = "REFERENCE"),
  @JsonSubTypes.Type(value = OperatorExpression.class, name = "OPERATOR")
})
public sealed interface Expression
    permits LiteralExpression, ReferenceExpression, OperatorExpression {}
