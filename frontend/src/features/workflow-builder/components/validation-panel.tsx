"use client";

import type { ValidationIssue } from "../types";
import { getValidationIssuePresentation } from "../validation-copy";

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
      className="absolute inset-x-0 bottom-0 z-20 max-h-64 min-w-0 overflow-hidden rounded-t-xl border-x border-t border-slate-200 bg-white shadow-xl"
    >
      <div className="flex items-center justify-between border-b border-slate-200 bg-slate-50 px-4 py-2.5">
        <div className="flex items-center gap-3">
          <h3 className="text-xs font-bold tracking-wider text-slate-900 uppercase">
            Vấn đề kiểm tra quy trình
          </h3>
          <div className="flex items-center gap-1.5">
            <span
              data-testid="validation-error-count"
              className="rounded-full bg-rose-100 px-2 py-0.5 text-[10px] font-bold text-rose-700"
            >
              {errorCount} lỗi
            </span>
            <span
              data-testid="validation-warning-count"
              className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold text-amber-800"
            >
              {warningCount} cảnh báo
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

      <div className="max-h-52 divide-y divide-slate-100 overflow-y-auto p-2">
        {issues.length === 0 ? (
          <div className="p-4 text-center text-xs font-medium text-emerald-600">
            ✓ Sơ đồ đã vượt qua kiểm tra, không có lỗi hoặc cảnh báo.
          </div>
        ) : (
          issues.map((issue) => {
            const presentation = getValidationIssuePresentation(issue);
            const hasTechnicalDetails = Boolean(
              issue.code || issue.nodeId || issue.edgeId || issue.field,
            );

            return (
              <div key={issue.id} className="p-2">
                <button
                  type="button"
                  data-testid={`validation-issue-${issue.id}`}
                  onClick={() => onSelectIssue(issue.nodeId, issue.field)}
                  className="flex w-full items-start gap-2.5 rounded-lg p-2 text-left transition-colors hover:bg-slate-50"
                >
                  {issue.severity === "ERROR" ? (
                    <span className="mt-0.5 shrink-0 rounded bg-rose-100 px-1.5 py-0.5 text-[9px] font-bold text-rose-700 uppercase">
                      Lỗi
                    </span>
                  ) : (
                    <span className="mt-0.5 shrink-0 rounded bg-amber-100 px-1.5 py-0.5 text-[9px] font-bold text-amber-800 uppercase">
                      Cảnh báo
                    </span>
                  )}
                  <span className="min-w-0 flex-1">
                    <span className="block text-xs font-semibold text-slate-800">
                      {presentation.message}
                    </span>
                    <span className="mt-1 block text-[11px] font-medium text-slate-500">
                      <span className="font-semibold text-slate-600">
                        Cách sửa:
                      </span>{" "}
                      {presentation.suggestion}
                    </span>
                  </span>
                  <span className="shrink-0 text-[11px] font-medium text-blue-600">
                    Đi tới bước →
                  </span>
                </button>
                {hasTechnicalDetails && (
                  <details className="mt-1 ml-8 text-[10px] text-slate-400">
                    <summary className="cursor-pointer select-none hover:text-slate-600">
                      Chi tiết kỹ thuật
                    </summary>
                    <div
                      data-testid="validation-issue-technical-details"
                      className="mt-1 space-y-0.5 rounded bg-slate-50 px-2 py-1 font-mono"
                    >
                      {issue.code && (
                        <div>
                          Mã:{" "}
                          <span data-testid="validation-issue-code">
                            {issue.code}
                          </span>
                        </div>
                      )}
                      {issue.nodeId && <div>Bước nội bộ: {issue.nodeId}</div>}
                      {issue.edgeId && (
                        <div>Liên kết nội bộ: {issue.edgeId}</div>
                      )}
                      {issue.field && <div>Trường nội bộ: {issue.field}</div>}
                    </div>
                  </details>
                )}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
