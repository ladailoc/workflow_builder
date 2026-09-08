import type {
  AtomicConditionClause,
  ConditionOperator,
  LogicalOperator,
  UiConditionGroup,
} from "../editor-types";

export interface AstReference {
  path: string;
  [key: string]: unknown;
}

export interface AstLiteral {
  value: unknown;
  type: string;
  [key: string]: unknown;
}

export interface AstOperator {
  operator: string;
  operands: (AstReference | AstLiteral | AstOperator)[];
  [key: string]: unknown;
}

export type ConditionAst = AstOperator | AstReference | AstLiteral;

/**
 * Parses user input string into a typed literal operand.
 */
function parseLiteralOperand(raw: string): AstLiteral {
  const trimmed = raw.trim();

  // Boolean
  if (trimmed.toLowerCase() === "true") {
    return { value: true, type: "BOOLEAN" };
  }
  if (trimmed.toLowerCase() === "false") {
    return { value: false, type: "BOOLEAN" };
  }

  // Integer
  if (/^-?\d+$/.test(trimmed)) {
    return { value: parseInt(trimmed, 10), type: "INTEGER" };
  }

  // Decimal
  if (/^-?\d+\.\d+$/.test(trimmed)) {
    return { value: parseFloat(trimmed), type: "DECIMAL" };
  }

  // Array (e.g. for IN operator)
  if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
    try {
      const parsed = JSON.parse(trimmed);
      if (Array.isArray(parsed)) {
        return { value: parsed, type: "ARRAY" };
      }
    } catch {
      // fallback to string
    }
  }

  // Fallback to String
  return { value: trimmed.replace(/^["']|["']$/g, ""), type: "STRING" };
}

/**
 * Compiles a single atomic clause into an AST operator expression.
 */
export function compileAtomicClause(clause: AtomicConditionClause): AstOperator {
  const pathOperand: AstReference = { path: clause.field.trim() };

  if (clause.operator === "IS_NULL") {
    return {
      operator: "IS_NULL",
      operands: [pathOperand],
    };
  }

  if (clause.operator === "NOT_NULL") {
    return {
      operator: "NOT",
      operands: [
        {
          operator: "IS_NULL",
          operands: [pathOperand],
        },
      ],
    };
  }

  const literalOperand = parseLiteralOperand(clause.value);
  const operator = clause.operator === "NEQ" ? "NE" : clause.operator;

  return {
    operator,
    operands: [pathOperand, literalOperand],
  };
}

/**
 * Compiles a full UI condition group with AND/OR/NOT into a typed AST.
 */
export function compileConditionExpression(group: UiConditionGroup): AstOperator {
  const operands = group.clauses.map((c) => {
    if ("logical" in c) {
      return compileConditionExpression(c);
    }
    return compileAtomicClause(c);
  });

  if (operands.length === 0) {
    return {
      operator: "EQ",
      operands: [
        { path: "system.alwaysTrue" },
        { value: true, type: "BOOLEAN" },
      ],
    };
  }

  return {
    operator: group.logical,
    operands,
  };
}

let clauseCounter = 1;

/**
 * Decompiles an AST expression into a UiConditionGroup structure.
 */
export function decompileConditionExpression(ast: Record<string, unknown>): UiConditionGroup {
  if (!ast || typeof ast !== "object") {
    return {
      id: `group_${clauseCounter++}`,
      logical: "AND",
      clauses: [
        {
          id: `clause_${clauseCounter++}`,
          field: "payload.amount",
          operator: "GT",
          value: "1000",
        },
      ],
    };
  }

  const operator = String(ast.operator || "AND").toUpperCase();

  if (operator === "AND" || operator === "OR" || operator === "NOT") {
    const rawOperands = Array.isArray(ast.operands) ? ast.operands : [];
    const clauses: (AtomicConditionClause | UiConditionGroup)[] = [];

    rawOperands.forEach((op) => {
      if (typeof op === "object" && op !== null) {
        const opObj = op as Record<string, unknown>;
        const subOp = String(opObj.operator || "").toUpperCase();
        if (subOp === "AND" || subOp === "OR" || subOp === "NOT") {
          clauses.push(decompileConditionExpression(opObj));
        } else {
          clauses.push(decompileAtomicAst(opObj));
        }
      }
    });

    return {
      id: `group_${clauseCounter++}`,
      logical: operator as LogicalOperator,
      clauses: clauses.length > 0 ? clauses : [
        {
          id: `clause_${clauseCounter++}`,
          field: "payload.amount",
          operator: "GT",
          value: "1000",
        },
      ],
    };
  }

  return {
    id: `group_${clauseCounter++}`,
    logical: "AND",
    clauses: [decompileAtomicAst(ast)],
  };
}

function decompileAtomicAst(ast: Record<string, unknown>): AtomicConditionClause {
  const op = String(ast.operator || "EQ").toUpperCase();
  const rawOperands = Array.isArray(ast.operands) ? ast.operands : [];

  let field = "payload.variable";
  let value = "";

  if (rawOperands[0] && typeof rawOperands[0] === "object") {
    const ref = rawOperands[0] as Record<string, unknown>;
    if (ref.path) field = String(ref.path);
  }

  if (rawOperands[1] && typeof rawOperands[1] === "object") {
    const lit = rawOperands[1] as Record<string, unknown>;
    if (lit.value !== undefined) {
      value = typeof lit.value === "object" ? JSON.stringify(lit.value) : String(lit.value);
    }
  }

  let mappedOp: ConditionOperator = "EQ";
  if (op === "NE") mappedOp = "NEQ";
  else if (
    [
      "EQ",
      "NEQ",
      "GT",
      "GTE",
      "LT",
      "LTE",
      "IN",
      "CONTAINS",
      "IS_NULL",
      "NOT_NULL",
    ].includes(op)
  ) {
    mappedOp = op as ConditionOperator;
  }

  return {
    id: `clause_${clauseCounter++}`,
    field,
    operator: mappedOp,
    value,
  };
}
