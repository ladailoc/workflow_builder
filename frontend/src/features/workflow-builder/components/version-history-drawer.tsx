"use client";

import type { WorkflowVersionDto } from "../types";
import { formatStatus } from "@/shared/components/ui/status-badge";

interface VersionHistoryDrawerProps {
  isOpen: boolean;
  currentVersion: WorkflowVersionDto;
  versions: WorkflowVersionDto[];
  onCloneAsNewDraft: (sourceVersion: WorkflowVersionDto) => void;
  onOpenDiff?: (compareVersion: WorkflowVersionDto) => void;
  onClose: () => void;
}

export function VersionHistoryDrawer({
  isOpen,
  currentVersion,
  versions,
  onCloneAsNewDraft,
  onOpenDiff,
  onClose,
}: VersionHistoryDrawerProps) {
  if (!isOpen) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      data-testid="version-history-drawer"
      className="fixed inset-0 z-50 flex justify-end bg-black/40 backdrop-blur-xs"
    >
      <div className="h-full w-full max-w-md bg-white p-6 shadow-2xl space-y-4 flex flex-col animate-slide-in-right">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-200 pb-3">
          <div>
            <h3 className="text-sm font-bold text-slate-900">
              Lịch sử phiên bản & khôi phục
            </h3>
          </div>
          <button
            type="button"
            data-testid="close-history-drawer-btn"
            onClick={onClose}
            className="rounded p-1 text-slate-400 hover:text-slate-600"
          >
            ✕
          </button>
        </div>

        {/* Versions List */}
        <div className="flex-1 overflow-y-auto space-y-2 pr-1">
          {versions.map((ver) => {
            const isCurrent = ver.id === currentVersion.id;

            return (
              <div
                key={ver.id}
                data-testid={`version-history-item-${ver.versionNo}`}
                className={`rounded-lg border p-3 text-xs space-y-2 transition-all ${
                  isCurrent
                    ? "border-blue-300 bg-blue-50/40 ring-1 ring-blue-300"
                    : "border-slate-200 bg-white shadow-2xs"
                }`}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="font-bold text-slate-900">
                      Phiên bản #{ver.versionNo}
                    </span>
                    {isCurrent && (
                      <span className="rounded bg-blue-600 px-1.5 py-0.2 text-[9px] font-bold text-white">
                        Hiện tại
                      </span>
                    )}
                  </div>
                  <span
                    className={`rounded-full border px-2 py-0.5 text-[10px] font-semibold ${
                      ver.status === "PUBLISHED"
                        ? "bg-emerald-100 text-emerald-800 border-emerald-300"
                        : ver.status === "DRAFT"
                          ? "bg-amber-100 text-amber-800 border-amber-300"
                          : "bg-slate-100 text-slate-700 border-slate-300"
                    }`}
                  >
                    {formatStatus(ver.status)}
                  </span>
                </div>

                <div className="flex items-center gap-3 text-[11px] text-slate-500 font-mono">
                  <span>{ver.nodes.length} bước</span>
                  <span>•</span>
                  <span>{ver.edges.length} chuyển tiếp</span>
                  <span>•</span>
                  <span>Rev {ver.revision}</span>
                </div>

                {/* Actions */}
                <div className="flex items-center justify-end gap-2 pt-1 border-t border-slate-100">
                  {onOpenDiff && !isCurrent && (
                    <button
                      type="button"
                      data-testid={`diff-version-btn-${ver.id}`}
                      onClick={() => onOpenDiff(ver)}
                      className="rounded border border-slate-200 px-2 py-1 text-[11px] font-semibold text-slate-600 hover:bg-slate-50"
                    >
                      So sánh thay đổi
                    </button>
                  )}

                  {!isCurrent && (
                    <button
                      type="button"
                      data-testid={`clone-version-btn-${ver.id}`}
                      onClick={() => onCloneAsNewDraft(ver)}
                      className="rounded bg-indigo-600 px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-indigo-700 shadow-2xs"
                    >
                      Sao chép thành bản nháp mới
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
