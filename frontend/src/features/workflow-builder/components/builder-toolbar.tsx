"use client";

import type { WorkflowVersionStatus } from "../types";
import { formatStatus } from "@/shared/components/ui/status-badge";

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
  onCloneAsNewDraft?: () => void;
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
  onCloneAsNewDraft,
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
    <header className="flex min-h-14 shrink-0 items-center gap-3 overflow-hidden border-b border-slate-200 bg-white px-3 shadow-2xs sm:px-5">
      {/* Left: Version & Mode Info */}
      <div className="flex min-w-0 max-w-[42%] shrink items-center gap-2">
        {workflowName && (
          <span className="min-w-0 max-w-52 truncate text-sm font-semibold text-slate-700">
            {workflowName}
          </span>
        )}
        <div className="flex min-w-0 shrink-0 items-center gap-1.5">
          <span className="whitespace-nowrap text-sm font-bold text-slate-900">
            Phiên bản #{versionNo}
          </span>
          <span
            data-testid="builder-version-status"
            className={`shrink-0 rounded-full border px-2 py-0.5 text-[11px] font-semibold ${getStatusBadge()}`}
          >
            {formatStatus(status)}
          </span>
        </div>

        {!isDraft && (
          <span
            data-testid="read-only-banner"
            className="shrink-0 whitespace-nowrap rounded-md bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-600"
          >
            Bảng vẽ chỉ xem
          </span>
        )}
      </div>

      {/* Right: Actions */}
      <div className="min-w-0 flex-1 overflow-x-auto">
        <div className="ml-auto flex w-max items-center gap-1.5 py-1">
        {onRequestForm && (
          <button
            type="button"
            data-testid="toolbar-request-form-button"
            onClick={onRequestForm}
            className="inline-flex h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-lg border border-slate-200 bg-white px-2.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
          >
            <span>Biểu mẫu yêu cầu</span>
          </button>
        )}

        <button
          type="button"
          data-testid="toolbar-validate-button"
          onClick={onValidate}
          className="relative inline-flex h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-lg border border-slate-200 bg-white px-2.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
        >
          <span>Kiểm tra</span>
          {issueCount > 0 && (
            <span
              data-testid="toolbar-issue-badge"
              className={`rounded-full px-1.5 py-0.5 text-[10px] font-bold text-white ${
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
          className="inline-flex h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-lg border border-slate-200 bg-white px-2.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
        >
          <span>Mô phỏng</span>
        </button>

        <button
          type="button"
          data-testid="toolbar-diff-button"
          onClick={onDiffHistory}
          className="inline-flex h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-lg border border-slate-200 bg-white px-2.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
        >
          <span>So sánh / Lịch sử</span>
        </button>

        <div className="mx-0.5 h-4 w-px shrink-0 bg-slate-200" />

        {status !== "DRAFT" && !isDraft && onCloneAsNewDraft && (
          <button
            type="button"
            data-testid="toolbar-clone-draft-button"
            onClick={onCloneAsNewDraft}
            className="inline-flex h-9 shrink-0 items-center whitespace-nowrap rounded-lg border border-blue-200 bg-blue-50 px-2.5 text-xs font-semibold text-blue-700 transition-colors hover:bg-blue-100"
            title="Sao chép phiên bản này thành bản nháp mới để chỉnh sửa"
          >
            Tạo bản nháp
          </button>
        )}

        <button
          type="button"
          data-testid="toolbar-save-draft-button"
          disabled={!isDraft}
          onClick={onSaveDraft}
          className="inline-flex h-9 shrink-0 items-center whitespace-nowrap rounded-lg border border-slate-300 bg-white px-2.5 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
        >
          Lưu bản nháp
        </button>

        <button
          type="button"
          data-testid="toolbar-publish-button"
          disabled={!isDraft || !publishAllowed || hasErrors}
          onClick={onPublish}
          className="inline-flex h-9 shrink-0 items-center whitespace-nowrap rounded-lg bg-blue-600 px-3 text-xs font-semibold text-white shadow-2xs transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-40"
          title={
            hasErrors
              ? "Hãy xử lý tất cả lỗi kiểm tra trước khi phát hành"
              : !isDraft
                ? "Chỉ bản nháp mới có thể phát hành"
                : !publishAllowed
                  ? "Bạn không có quyền phát hành"
                  : "Phát hành phiên bản này"
          }
        >
          Phát hành
        </button>
        </div>
      </div>
    </header>
  );
}
