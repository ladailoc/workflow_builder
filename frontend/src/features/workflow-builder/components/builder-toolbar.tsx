"use client";

import type { WorkflowVersionStatus } from "../types";

interface BuilderToolbarProps {
  workflowName?: string;
  versionNo: number;
  status: WorkflowVersionStatus;
  editingAllowed?: boolean;
  publishAllowed?: boolean;
  hasErrors: boolean;
  issueCount: number;
  onSaveDraft: () => void;
  onValidate: () => void;
  onSimulate: () => void;
  onDiffHistory: () => void;
  onPublish: () => void;
  onRequestForm?: () => void;
}

export function BuilderToolbar({
  workflowName,
  versionNo,
  status,
  editingAllowed = true,
  publishAllowed = true,
  hasErrors,
  issueCount,
  onSaveDraft,
  onValidate,
  onSimulate,
  onDiffHistory,
  onPublish,
  onRequestForm,
}: BuilderToolbarProps) {
  const isDraft = status === "DRAFT" && editingAllowed;

  const getStatusBadge = () => {
    switch (status) {
      case "PUBLISHED":
        return "bg-emerald-100 text-emerald-800 border-emerald-300";
      case "SUPERSEDED":
        return "bg-slate-100 text-slate-700 border-slate-300";
      case "ARCHIVED":
        return "bg-rose-100 text-rose-800 border-rose-300";
      default:
        return "bg-amber-100 text-amber-800 border-amber-300";
    }
  };

  return (
    <header className="flex h-14 shrink-0 items-center justify-between border-b border-slate-200 bg-white px-5 shadow-2xs">
      {/* Left: Version & Mode Info */}
      <div className="flex items-center gap-3">
        {workflowName && (
          <span className="max-w-64 truncate text-sm font-semibold text-slate-700">
            {workflowName}
          </span>
        )}
        <div className="flex items-center gap-2">
          <span className="text-sm font-bold text-slate-900">
            Version #{versionNo}
          </span>
          <span
            data-testid="builder-version-status"
            className={`rounded-full border px-2.5 py-0.5 text-xs font-semibold ${getStatusBadge()}`}
          >
            {status}
          </span>
        </div>

        {!isDraft && (
          <span
            data-testid="read-only-banner"
            className="rounded-md bg-slate-100 px-2 py-0.5 text-xs font-semibold text-slate-600"
          >
            Read-Only Canvas
          </span>
        )}
      </div>

      {/* Right: Actions */}
      <div className="flex items-center gap-2">
        {onRequestForm && (
          <button
            type="button"
            data-testid="toolbar-request-form-button"
            onClick={onRequestForm}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
          >
            <span>Request Form</span>
          </button>
        )}

        <button
          type="button"
          data-testid="toolbar-validate-button"
          onClick={onValidate}
          className="relative inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
        >
          <span>Validate</span>
          {issueCount > 0 && (
            <span
              data-testid="toolbar-issue-badge"
              className={`py-0.2 rounded-full px-1.5 text-[10px] font-bold text-white ${
                hasErrors ? "bg-rose-600" : "bg-amber-500"
              }`}
            >
              {issueCount}
            </span>
          )}
        </button>

        <button
          type="button"
          data-testid="toolbar-simulate-button"
          onClick={onSimulate}
          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
        >
          <span>Simulate</span>
        </button>

        <button
          type="button"
          data-testid="toolbar-diff-button"
          onClick={onDiffHistory}
          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
        >
          <span>Diff / History</span>
        </button>

        <div className="mx-1 h-4 w-px bg-slate-200" />

        <button
          type="button"
          data-testid="toolbar-save-draft-button"
          disabled={!isDraft}
          onClick={onSaveDraft}
          className="rounded-lg border border-slate-300 bg-white px-3.5 py-1.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
        >
          Save Draft
        </button>

        <button
          type="button"
          data-testid="toolbar-publish-button"
          disabled={!isDraft || !publishAllowed || hasErrors}
          onClick={onPublish}
          className="rounded-lg bg-blue-600 px-4 py-1.5 text-xs font-semibold text-white shadow-2xs transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-40"
          title={
            hasErrors
              ? "Resolve all validation errors before publishing"
              : !isDraft
                ? "Only drafts can be published"
                : !publishAllowed
                  ? "You do not have permission to publish"
                  : "Publish this version"
          }
        >
          Publish
        </button>
      </div>
    </header>
  );
}
