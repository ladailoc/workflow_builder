"use client";

import { useId, useState } from "react";
import type {
  AtomicConditionClause,
  ConditionOperator,
  LogicalOperator,
  UiConditionGroup,
} from "../editor-types";
import {
  compileConditionExpression,
  decompileConditionExpression,
  type AstOperator,
} from "../utils/condition-compiler";

const OPERATORS: { value: ConditionOperator; label: string }[] = [
  { value: "EQ", label: "==" },
  { value: "NEQ", label: "!=" },
  { value: "GT", label: ">" },
  { value: "GTE", label: ">=" },
  { value: "LT", label: "<" },
  { value: "LTE", label: "<=" },
  { value: "IN", label: "IN (list)" },
  { value: "CONTAINS", label: "CONTAINS" },
  { value: "IS_NULL", label: "IS NULL" },
  { value: "NOT_NULL", label: "NOT NULL" },
];

const CONTEXT_PREFIXES = [
  "payload.totalAmount",
  "payload.departmentId",
  "payload.category",
  "event.creatorId",
  "event.priority",
  "system.iterationCount",
  "system.currentDate",
];

interface ConditionExpressionEditorProps {
  value?: Record<string, unknown> | string;
  onChange: (compiled: AstOperator) => void;
  readOnly?: boolean;
}

let uniqueId = 100;

export function ConditionExpressionEditor({
  value,
  onChange,
  readOnly = false,
}: ConditionExpressionEditorProps) {
  const [rootGroup, setRootGroup] = useState<UiConditionGroup>(() => {
    if (typeof value === "object" && value !== null) {
      return decompileConditionExpression(value);
    }
    return {
      id: `root_${uniqueId++}`,
      logical: "AND",
      clauses: [
        {
          id: `clause_${uniqueId++}`,
          field: "payload.totalAmount",
          operator: "GT",
          value: "5000",
        },
      ],
    };
  });

  const formHtmlId = useId();

  const handleUpdateRoot = (updated: UiConditionGroup) => {
    if (readOnly) return;
    setRootGroup(updated);
    onChange(compileConditionExpression(updated));
  };

  return (
    <div className="space-y-3" data-testid="condition-expression-editor">
      <div className="flex items-center justify-between border-b border-slate-200 pb-1.5">
        <span className="text-xs font-bold text-slate-800">
          Condition Expression Builder
        </span>
        <span className="font-mono text-[10px] text-slate-500">
          Safe AST Engine
        </span>
      </div>

      <RenderGroup
        group={rootGroup}
        onChange={handleUpdateRoot}
        readOnly={readOnly}
        isRoot
        formHtmlId={formHtmlId}
      />
    </div>
  );
}

function RenderGroup({
  group,
  onChange,
  readOnly,
  isRoot = false,
  formHtmlId,
}: {
  group: UiConditionGroup;
  onChange: (updated: UiConditionGroup) => void;
  readOnly: boolean;
  isRoot?: boolean;
  formHtmlId: string;
}) {
  const setLogical = (logical: LogicalOperator) => {
    if (readOnly) return;
    onChange({ ...group, logical });
  };

  const handleAddClause = () => {
    if (readOnly) return;
    const nextClauses = [
      ...group.clauses,
      {
        id: `clause_${uniqueId++}`,
        field: "payload.amount",
        operator: "GT" as ConditionOperator,
        value: "0",
      },
    ];
    onChange({ ...group, clauses: nextClauses });
  };

  const handleAddSubGroup = () => {
    if (readOnly) return;
    const nextClauses = [
      ...group.clauses,
      {
        id: `group_${uniqueId++}`,
        logical: "OR" as LogicalOperator,
        clauses: [
          {
            id: `clause_${uniqueId++}`,
            field: "event.priority",
            operator: "GTE" as ConditionOperator,
            value: "80",
          },
        ],
      },
    ];
    onChange({ ...group, clauses: nextClauses });
  };

  const handleRemoveClause = (idx: number) => {
    if (readOnly) return;
    const nextClauses = group.clauses.filter((_, i) => i !== idx);
    onChange({ ...group, clauses: nextClauses });
  };

  const handleUpdateClause = (
    idx: number,
    updated: AtomicConditionClause | UiConditionGroup,
  ) => {
    if (readOnly) return;
    const nextClauses = [...group.clauses];
    nextClauses[idx] = updated;
    onChange({ ...group, clauses: nextClauses });
  };

  return (
    <div
      data-testid={`condition-group-${group.id}`}
      className={`rounded-lg border p-3 space-y-3 ${
        isRoot
          ? "border-slate-300 bg-slate-50/50"
          : "border-blue-200 bg-blue-50/30 ml-3"
      }`}
    >
      {/* Logical Operator Selector */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-[11px] font-bold text-slate-700">Match:</span>
          <div className="flex rounded border border-slate-300 bg-white p-0.5">
            {(["AND", "OR", "NOT"] as LogicalOperator[]).map((op) => (
              <button
                key={op}
                type="button"
                data-testid={`btn-logical-${op.toLowerCase()}`}
                disabled={readOnly}
                onClick={() => setLogical(op)}
                className={`rounded px-2 py-0.5 font-mono text-[10px] font-bold transition-colors ${
                  group.logical === op
                    ? "bg-blue-600 text-white shadow-2xs"
                    : "text-slate-600 hover:text-slate-900"
                }`}
              >
                {op}
              </button>
            ))}
          </div>
          <span className="text-[10px] text-slate-400">
            {group.logical === "AND" && "(All clauses must be TRUE)"}
            {group.logical === "OR" && "(Any clause must be TRUE)"}
            {group.logical === "NOT" && "(Inverts result of clause)"}
          </span>
        </div>

        {!readOnly && (
          <div className="flex items-center gap-1">
            <button
              type="button"
              data-testid="btn-add-clause"
              onClick={handleAddClause}
              className="rounded bg-white border border-slate-200 px-2 py-1 text-[10px] font-semibold text-slate-700 hover:bg-slate-50 shadow-2xs"
            >
              + Clause
            </button>
            <button
              type="button"
              data-testid="btn-add-subgroup"
              onClick={handleAddSubGroup}
              className="rounded bg-white border border-slate-200 px-2 py-1 text-[10px] font-semibold text-slate-700 hover:bg-slate-50 shadow-2xs"
            >
              + Group
            </button>
          </div>
        )}
      </div>

      {/* Clauses List */}
      <div className="space-y-2">
        {group.clauses.map((clause, idx) => {
          if ("logical" in clause) {
            return (
              <div key={clause.id} className="relative">
                <RenderGroup
                  group={clause}
                  onChange={(upd) => handleUpdateClause(idx, upd)}
                  readOnly={readOnly}
                  formHtmlId={`${formHtmlId}-sub-${idx}`}
                />
                {!readOnly && (
                  <button
                    type="button"
                    data-testid={`btn-remove-subgroup-${idx}`}
                    onClick={() => handleRemoveClause(idx)}
                    className="absolute top-2 right-2 text-[10px] text-rose-600 hover:text-rose-800"
                  >
                    Remove Group
                  </button>
                )}
              </div>
            );
          }

          const atomic = clause as AtomicConditionClause;
          const isNullOp =
            atomic.operator === "IS_NULL" || atomic.operator === "NOT_NULL";

          return (
            <div
              key={atomic.id}
              data-testid={`condition-clause-row-${idx}`}
              className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white p-2 shadow-2xs"
            >
              {/* Field with context variable hints */}
              <div className="flex-1">
                <input
                  type="text"
                  data-testid={`input-clause-field-${idx}`}
                  disabled={readOnly}
                  value={atomic.field}
                  onChange={(e) =>
                    handleUpdateClause(idx, {
                      ...atomic,
                      field: e.target.value,
                    })
                  }
                  list={`context-vars-${atomic.id}`}
                  placeholder="payload.* or event.*"
                  className="w-full rounded border border-slate-300 px-2 py-1 font-mono text-xs text-slate-800 disabled:bg-slate-100"
                />
                <datalist id={`context-vars-${atomic.id}`}>
                  {CONTEXT_PREFIXES.map((p) => (
                    <option key={p} value={p} />
                  ))}
                </datalist>
              </div>

              {/* Operator */}
              <select
                data-testid={`select-clause-operator-${idx}`}
                disabled={readOnly}
                value={atomic.operator}
                onChange={(e) =>
                  handleUpdateClause(idx, {
                    ...atomic,
                    operator: e.target.value as ConditionOperator,
                  })
                }
                className="rounded border border-slate-300 bg-white px-2 py-1 font-mono text-xs font-semibold text-slate-700 disabled:bg-slate-100"
              >
                {OPERATORS.map((op) => (
                  <option key={op.value} value={op.value}>
                    {op.label}
                  </option>
                ))}
              </select>

              {/* Value (hidden if IS_NULL / NOT_NULL) */}
              {!isNullOp && (
                <div className="flex-1">
                  <input
                    type="text"
                    data-testid={`input-clause-value-${idx}`}
                    disabled={readOnly}
                    value={atomic.value}
                    onChange={(e) =>
                      handleUpdateClause(idx, {
                        ...atomic,
                        value: e.target.value,
                      })
                    }
                    placeholder="Literal value or list [1, 2]"
                    className="w-full rounded border border-slate-300 px-2 py-1 font-mono text-xs text-slate-800 disabled:bg-slate-100"
                  />
                </div>
              )}

              {/* Remove Clause */}
              {!readOnly && group.clauses.length > 1 && (
                <button
                  type="button"
                  data-testid={`btn-remove-clause-${idx}`}
                  onClick={() => handleRemoveClause(idx)}
                  className="rounded p-1 text-xs text-slate-400 hover:text-rose-600"
                  title="Remove clause"
                >
                  ✕
                </button>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
