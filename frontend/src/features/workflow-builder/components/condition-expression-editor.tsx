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
import type { FormFieldDefinition } from "../form-types";

const OPERATORS: { value: ConditionOperator; label: string }[] = [
  { value: "EQ", label: "Bằng (==)" },
  { value: "NEQ", label: "Khác (!=)" },
  { value: "GT", label: "Lớn hơn (>)" },
  { value: "GTE", label: "Lớn hơn hoặc bằng (>=)" },
  { value: "LT", label: "Nhỏ hơn (<)" },
  { value: "LTE", label: "Nhỏ hơn hoặc bằng (<=)" },
  { value: "IN", label: "Nằm trong danh sách" },
  { value: "CONTAINS", label: "Có chứa" },
  { value: "IS_NULL", label: "Để trống" },
  { value: "NOT_NULL", label: "Có giá trị" },
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
  availableFields?: FormFieldDefinition[];
}

let uniqueId = 100;

function defaultClauseForField(
  field: FormFieldDefinition | undefined,
  id: string,
): AtomicConditionClause {
  if (!field) {
    return {
      id,
      field: "payload.amount",
      operator: "GT",
      value: "0",
    };
  }

  const value =
    field.type === "BOOLEAN"
      ? "true"
      : field.type === "ENUM"
        ? (field.options?.[0] ?? "")
        : field.type === "INTEGER" || field.type === "DECIMAL"
          ? "0"
          : "";
  const operator =
    field.type === "FILE"
      ? "NOT_NULL"
      : field.type === "STRING" ||
          field.type === "ENUM" ||
          field.type === "BOOLEAN"
        ? "EQ"
        : field.type === "DATE"
          ? "EQ"
          : "GT";

  return {
    id,
    field: `payload.${field.key}`,
    operator,
    value,
  };
}

export function ConditionExpressionEditor({
  value,
  onChange,
  readOnly = false,
  availableFields = [],
}: ConditionExpressionEditorProps) {
  const [rootGroup, setRootGroup] = useState<UiConditionGroup>(() => {
    if (typeof value === "object" && value !== null) {
      return decompileConditionExpression(value);
    }
    if (typeof value === "string" && value.trim()) {
      try {
        const parsed = JSON.parse(value) as unknown;
        if (typeof parsed === "object" && parsed !== null) {
          return decompileConditionExpression(parsed as Record<string, unknown>);
        }
      } catch {
        // Keep the guided default when a legacy expression is not valid JSON.
      }
    }
    return {
      id: `root_${uniqueId++}`,
      logical: "AND",
      clauses: [
        defaultClauseForField(availableFields[0], `clause_${uniqueId++}`),
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
        <span className="text-xs font-bold text-slate-800">Tạo điều kiện</span>
        <span className="text-[10px] text-slate-500">
          Chọn trường, toán tử và giá trị
        </span>
      </div>

      <RenderGroup
        group={rootGroup}
        onChange={handleUpdateRoot}
        readOnly={readOnly}
        isRoot
        formHtmlId={formHtmlId}
        availableFields={availableFields}
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
  availableFields = [],
}: {
  group: UiConditionGroup;
  onChange: (updated: UiConditionGroup) => void;
  readOnly: boolean;
  isRoot?: boolean;
  formHtmlId: string;
  availableFields?: FormFieldDefinition[];
}) {
  const schemaFieldOptions = availableFields.map((field) => ({
    value: `payload.${field.key}`,
    label: `${field.label} · ${field.type}`,
  }));
  const schemaFieldValues = new Set(
    schemaFieldOptions.map((field) => field.value),
  );
  const fieldOptions = [
    ...schemaFieldOptions,
    ...CONTEXT_PREFIXES.filter((field) => !schemaFieldValues.has(field)).map(
      (field) => ({ value: field, label: field }),
    ),
  ];
  const setLogical = (logical: LogicalOperator) => {
    if (readOnly) return;
    onChange({ ...group, logical });
  };

  const handleAddClause = () => {
    if (readOnly) return;
    const nextClauses = [
      ...group.clauses,
      defaultClauseForField(availableFields[0], `clause_${uniqueId++}`),
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
          defaultClauseForField(availableFields[0], `clause_${uniqueId++}`),
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
      className={`space-y-3 rounded-lg border p-3 ${
        isRoot
          ? "border-slate-300 bg-slate-50/50"
          : "ml-3 border-blue-200 bg-blue-50/30"
      }`}
    >
      {/* Logical Operator Selector */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-[11px] font-bold text-slate-700">
            Điều kiện:
          </span>
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
            {group.logical === "AND" && "(Tất cả mệnh đề phải ĐÚNG)"}
            {group.logical === "OR" && "(Một mệnh đề đúng là đủ)"}
            {group.logical === "NOT" && "(Đảo ngược kết quả mệnh đề)"}
          </span>
        </div>

        {!readOnly && (
          <div className="flex items-center gap-1">
            <button
              type="button"
              data-testid="btn-add-clause"
              onClick={handleAddClause}
              className="rounded border border-slate-200 bg-white px-2 py-1 text-[10px] font-semibold text-slate-700 shadow-2xs hover:bg-slate-50"
            >
              + Thêm mệnh đề
            </button>
            <button
              type="button"
              data-testid="btn-add-subgroup"
              onClick={handleAddSubGroup}
              className="rounded border border-slate-200 bg-white px-2 py-1 text-[10px] font-semibold text-slate-700 shadow-2xs hover:bg-slate-50"
            >
              + Thêm nhóm
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
                  availableFields={availableFields}
                />
                {!readOnly && (
                  <button
                    type="button"
                    data-testid={`btn-remove-subgroup-${idx}`}
                    onClick={() => handleRemoveClause(idx)}
                    className="absolute top-2 right-2 text-[10px] text-rose-600 hover:text-rose-800"
                  >
                    Xóa nhóm
                  </button>
                )}
              </div>
            );
          }

          const atomic = clause as AtomicConditionClause;
          const isNullOp =
            atomic.operator === "IS_NULL" || atomic.operator === "NOT_NULL";
          const selectedField = availableFields.find(
            (field) => `payload.${field.key}` === atomic.field,
          );

          return (
            <div
              key={atomic.id}
              data-testid={`condition-clause-row-${idx}`}
              className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white p-2 shadow-2xs"
            >
              {/* Field selector backed by the request form schema */}
              <div className="flex-1">
                {availableFields.length > 0 ? (
                  <select
                    data-testid={`select-clause-field-${idx}`}
                    disabled={readOnly}
                    value={atomic.field}
                    onChange={(e) => {
                      const nextField = availableFields.find(
                        (field) => `payload.${field.key}` === e.target.value,
                      );
                      const defaults = defaultClauseForField(
                        nextField,
                        atomic.id,
                      );
                      handleUpdateClause(idx, {
                        ...atomic,
                        field: e.target.value,
                        operator: nextField
                          ? defaults.operator
                          : atomic.operator,
                        value: nextField ? defaults.value : atomic.value,
                      });
                    }}
                    className="w-full rounded border border-slate-300 bg-white px-2 py-1 text-xs text-slate-800 disabled:bg-slate-100"
                  >
                    {!fieldOptions.some(
                      (option) => option.value === atomic.field,
                    ) && <option value={atomic.field}>{atomic.field}</option>}
                    {fieldOptions.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                ) : (
                  <>
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
                      placeholder="payload.* hoặc event.*"
                      className="w-full rounded border border-slate-300 px-2 py-1 font-mono text-xs text-slate-800 disabled:bg-slate-100"
                    />
                    <datalist id={`context-vars-${atomic.id}`}>
                      {CONTEXT_PREFIXES.map((p) => (
                        <option key={p} value={p} />
                      ))}
                    </datalist>
                  </>
                )}
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

              {/* Value control adapts to the selected field type */}
              {!isNullOp && (
                <div className="flex-1">
                  {selectedField?.type === "BOOLEAN" ? (
                    <select
                      data-testid={`input-clause-value-${idx}`}
                      disabled={readOnly}
                      value={atomic.value}
                      onChange={(e) =>
                        handleUpdateClause(idx, {
                          ...atomic,
                          value: e.target.value,
                        })
                      }
                      className="w-full rounded border border-slate-300 bg-white px-2 py-1 text-xs text-slate-800 disabled:bg-slate-100"
                    >
                      <option value="true">Đúng (true)</option>
                      <option value="false">Sai (false)</option>
                    </select>
                  ) : selectedField?.type === "ENUM" &&
                    selectedField.options &&
                    selectedField.options.length > 0 &&
                    atomic.operator !== "IN" ? (
                    <select
                      data-testid={`input-clause-value-${idx}`}
                      disabled={readOnly}
                      value={atomic.value}
                      onChange={(e) =>
                        handleUpdateClause(idx, {
                          ...atomic,
                          value: e.target.value,
                        })
                      }
                      className="w-full rounded border border-slate-300 bg-white px-2 py-1 text-xs text-slate-800 disabled:bg-slate-100"
                    >
                      {selectedField.options.map((option) => (
                        <option key={option} value={option}>
                          {option}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <input
                      type={
                        selectedField?.type === "DATE"
                          ? "date"
                          : selectedField?.type === "INTEGER" ||
                              selectedField?.type === "DECIMAL"
                            ? "number"
                            : "text"
                      }
                      step={
                        selectedField?.type === "DECIMAL" ? "any" : undefined
                      }
                      data-testid={`input-clause-value-${idx}`}
                      disabled={readOnly}
                      value={atomic.value}
                      onChange={(e) =>
                        handleUpdateClause(idx, {
                          ...atomic,
                          value: e.target.value,
                        })
                      }
                      placeholder="Giá trị hoặc danh sách [1, 2]"
                      className="w-full rounded border border-slate-300 px-2 py-1 text-xs text-slate-800 disabled:bg-slate-100"
                    />
                  )}
                </div>
              )}

              {/* Remove Clause */}
              {!readOnly && group.clauses.length > 1 && (
                <button
                  type="button"
                  data-testid={`btn-remove-clause-${idx}`}
                  onClick={() => handleRemoveClause(idx)}
                  className="rounded p-1 text-xs text-slate-400 hover:text-rose-600"
                  title="Xóa mệnh đề"
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
