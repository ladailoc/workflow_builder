"use client";

import type { ValidationIssue } from "../types";

interface ValidationPanelProps {
  issues: ValidationIssue[];
  isOpen: boolean;
  onClose: () => void;
  onSelectIssue: (nodeId?: string, field?: string) => void;
}

export function ValidationPanel({
  issues,
  isOpen,
  onClose,
  onSelectIssue,
}: ValidationPanelProps) {
  if (!isOpen) return null;

  const errorCount = issues.filter((i) => i.severity === "ERROR").length;
  const warningCount = issues.filter((i) => i.severity === "WARNING").length;

  return (
    <div
      data-testid="validation-panel"
      className="absolute bottom-0 left-72 right-80 z-20 max-h-64 overflow-hidden rounded-t-xl border-t border-x border-slate-200 bg-white shadow-xl"
    >
      <div className="flex items-center justify-between border-b border-slate-200 bg-slate-50 px-4 py-2.5">
        <div className="flex items-center gap-3">
          <h3 className="text-xs font-bold text-slate-900 uppercase tracking-wider">
            Compiler Validation Issues
          </h3>
          <div className="flex items-center gap-1.5">
            <span
              data-testid="validation-error-count"
              className="rounded-full bg-rose-100 px-2 py-0.5 text-[10px] font-bold text-rose-700"
            >
              {errorCount} Error{errorCount === 1 ? "" : "s"}
            </span>
            <span
              data-testid="validation-warning-count"
              className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold text-amber-800"
            >
              {warningCount} Warning{warningCount === 1 ? "" : "s"}
            </span>
          </div>
        </div>

        <button
          type="button"
          data-testid="close-validation-panel"
          onClick={onClose}
          className="rounded p-1 text-slate-400 hover:text-slate-700"
        >
          ✕
        </button>
      </div>

      <div className="max-h-52 overflow-y-auto divide-y divide-slate-100 p-2">
        {issues.length === 0 ? (
          <div className="p-4 text-center text-xs text-emerald-600 font-medium">
            ✓ Graph passes compiler validation with zero errors and warnings.
          </div>
        ) : (
          issues.map((issue) => (
            <button
              key={issue.id}
              type="button"
              data-testid={`validation-issue-${issue.id}`}
              onClick={() => onSelectIssue(issue.nodeId, issue.field)}
              className="flex w-full items-start gap-2.5 rounded-lg p-2 text-left hover:bg-slate-50 transition-colors"
            >
              {issue.severity === "ERROR" ? (
                <span className="mt-0.5 shrink-0 rounded bg-rose-100 px-1.5 py-0.2 text-[9px] font-bold text-rose-700 uppercase">
                  Error
                </span>
              ) : (
                <span className="mt-0.5 shrink-0 rounded bg-amber-100 px-1.5 py-0.2 text-[9px] font-bold text-amber-800 uppercase">
                  Warning
                </span>
              )}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-1.5 mb-0.5">
                  {issue.code && (
                    <span
                      data-testid="validation-issue-code"
                      className="rounded bg-slate-100 px-1 py-0.2 font-mono text-[9px] font-bold text-slate-600"
                    >
                      {issue.code}
                    </span>
                  )}
                  <p className="text-xs font-semibold text-slate-800">
                    {issue.message}
                  </p>
                </div>
                {(issue.nodeId || issue.edgeId) && (
                  <p className="text-[10px] font-mono text-slate-400">
                    {issue.nodeId ? `Node: ${issue.nodeId}` : `Edge: ${issue.edgeId}`}{" "}
                    {issue.field ? `• Field: ${issue.field}` : ""}
                  </p>
                )}
              </div>
              <span className="text-[11px] text-blue-600 font-medium shrink-0">
                Focus →
              </span>
            </button>
          ))
        )}
      </div>
    </div>
  );
}
