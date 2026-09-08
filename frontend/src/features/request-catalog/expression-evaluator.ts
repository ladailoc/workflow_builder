import type { Expression } from "./types";

/**
 * Safely evaluates an Expression AST against form values.
 * Returns boolean or raw value based on operator.
 */
export function evaluateExpression(
  expr: Expression | null | undefined,
  data: Record<string, unknown>,
): unknown {
  if (!expr) {
    return true;
  }

  switch (expr.kind) {
    case "LITERAL":
      return expr.value;

    case "REFERENCE": {
      const rawPath = typeof expr.path === "string" ? expr.path : expr.path.path;
      // path might be "data.amount" or "amount" or "ticket.data.amount"
      const normalizedPath = rawPath.replace(/^(data\.|ticket\.data\.)/, "");
      return resolvePath(data, normalizedPath);
    }

    case "OPERATOR": {
      const { operator, operands } = expr;
      const evaluated = operands.map((op) => evaluateExpression(op, data));

      switch (operator) {
        case "EQ":
          return looseEquals(evaluated[0], evaluated[1]);
        case "NE":
          return !looseEquals(evaluated[0], evaluated[1]);
        case "GT":
          return Number(evaluated[0]) > Number(evaluated[1]);
        case "GTE":
          return Number(evaluated[0]) >= Number(evaluated[1]);
        case "LT":
          return Number(evaluated[0]) < Number(evaluated[1]);
        case "LTE":
          return Number(evaluated[0]) <= Number(evaluated[1]);
        case "IN":
          if (Array.isArray(evaluated[1])) {
            return evaluated[1].some((item) => looseEquals(item, evaluated[0]));
          }
          return false;
        case "CONTAINS":
          if (typeof evaluated[0] === "string") {
            return evaluated[0].includes(String(evaluated[1] ?? ""));
          }
          if (Array.isArray(evaluated[0])) {
            return evaluated[0].some((item) => looseEquals(item, evaluated[1]));
          }
          return false;
        case "IS_NULL":
          return (
            evaluated[0] === null ||
            evaluated[0] === undefined ||
            evaluated[0] === ""
          );
        case "AND":
          return evaluated.every(Boolean);
        case "OR":
          return evaluated.some(Boolean);
        case "NOT":
          return !evaluated[0];
        default:
          return false;
      }
    }

    default:
      return false;
  }
}

function resolvePath(obj: Record<string, unknown>, path: string): unknown {
  const parts = path.split(".");
  let current: unknown = obj;
  for (const part of parts) {
    if (current === null || current === undefined) {
      return undefined;
    }
    if (typeof current === "object") {
      current = (current as Record<string, unknown>)[part];
    } else {
      return undefined;
    }
  }
  return current;
}

function looseEquals(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (a == null && b == null) return true;
  if (typeof a === "number" && typeof b === "string") {
    return a === Number(b);
  }
  if (typeof a === "string" && typeof b === "number") {
    return Number(a) === b;
  }
  if (typeof a === "boolean" || typeof b === "boolean") {
    return Boolean(a) === Boolean(b);
  }
  return false;
}
