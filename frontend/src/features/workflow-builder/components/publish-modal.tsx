"use client";

import { useMemo, useState } from "react";
import type { BuilderEdge, BuilderNode, ValidationIssue, WorkflowVersionDto } from "../types";
import { validateWorkflowGraph } from "../validator";

interface PublishModalProps {
  isOpen: boolean;
  version: WorkflowVersionDto;
  nodes: BuilderNode[];
  edges: BuilderEdge[];
  onConfirmPublish: () => Promise<void>;
  onClose: () => void;
  onSelectIssue?: (nodeId?: string, field?: string) => void;
}

export function PublishModal({
  isOpen,
  version,
  nodes,
  edges,
  onConfirmPublish,
  onClose,
  onSelectIssue,
}: PublishModalProps) {
  // Enforce validation right before publish
  const validationIssues: ValidationIssue[] = useMemo(
    () => validateWorkflowGraph(nodes, edges),
    [nodes, edges],
  );

  const errorIssues = validationIssues.filter((i) => i.severity === "ERROR");
  const warningIssues = validationIssues.filter((i) => i.severity === "WARNING");
  const isBlocked = errorIssues.length > 0;

  const [isPublishing, setIsPublishing] = useState(false);
  const [publishSuccess, setPublishSuccess] = useState(false);

  if (!isOpen) return null;

  const handlePublish = async () => {
    if (isBlocked || isPublishing) return;
    setIsPublishing(true);
    try {
      await onConfirmPublish();
      setPublishSuccess(true);
      setTimeout(() => {
        onClose();
      }, 1500);
    } finally {
      setIsPublishing(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      data-testid="publish-modal"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-xs p-4"
    >
      <div className="w-full max-w-lg rounded-xl bg-white p-6 shadow-2xl space-y-4 animate-scale-in">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-200 pb-3">
          <div>
            <h3 className="text-sm font-bold text-slate-900">
              Publish Workflow Release #{version.versionNo}
            </h3>
            <p className="text-xs text-slate-500">
              Promotes draft into an immutable production release.
            </p>
          </div>
          <button
            type="button"
            data-testid="close-publish-modal-btn"
            onClick={onClose}
            className="rounded p-1 text-slate-400 hover:text-slate-600"
          >
            ✕
          </button>
        </div>

        {/* Success Feedback */}
        {publishSuccess ? (
          <div
            data-testid="publish-success-message"
            className="rounded-lg bg-emerald-50 border border-emerald-200 p-4 text-center text-xs font-semibold text-emerald-800 space-y-1"
          >
            <p className="text-sm font-bold">✓ Successfully Published!</p>
            <p className="text-[11px] text-emerald-700">
              Version #{version.versionNo} is now published and active.
            </p>
          </div>
        ) : (
          <>
            {/* Publication Pre-Validation Gate */}
            {isBlocked ? (
              <div
                data-testid="publish-blocked-alert"
                className="rounded-lg border border-rose-300 bg-rose-50 p-3.5 space-y-2 text-xs text-rose-900"
              >
                <div className="flex items-center gap-2 font-bold text-rose-800">
                  <span className="flex h-5 w-5 items-center justify-center rounded-full bg-rose-200 text-rose-800 font-mono text-[10px]">
                    !
                  </span>
                  <span>Publication Blocked: {errorIssues.length} Error(s) Detected</span>
                </div>
                <p className="text-[11px] text-rose-700">
                  Workflow invariants must be completely satisfied before publishing to production.
                </p>
                <div className="max-h-36 overflow-y-auto space-y-1.5 pt-1">
                  {errorIssues.map((err) => (
                    <div
                      key={err.id}
                      data-testid={`publish-error-item-${err.id}`}
                      className="rounded border border-rose-200 bg-white p-2 text-[11px] flex items-center justify-between"
                    >
                      <span className="font-medium text-slate-800">{err.message}</span>
                      {err.nodeId && onSelectIssue && (
                        <button
                          type="button"
                          onClick={() => {
                            onSelectIssue(err.nodeId, err.field);
                            onClose();
                          }}
                          className="text-blue-600 hover:underline shrink-0 ml-2"
                        >
                          Focus →
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            ) : (
              <div
                data-testid="publish-validation-clean"
                className="rounded-lg border border-emerald-200 bg-emerald-50/60 p-3 text-xs text-emerald-900 flex items-center gap-2"
              >
                <span className="font-bold text-emerald-700">✓ Invariants Passed:</span>
                <span>All graph compiler validation rules satisfied with 0 errors.</span>
              </div>
            )}

            {/* Publication Summary */}
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 space-y-2 text-xs">
              <span className="font-bold text-slate-800 block">
                Publication Summary
              </span>
              <div className="grid grid-cols-3 gap-2">
                <div className="rounded bg-white p-2 border border-slate-200 shadow-2xs">
                  <span className="text-[10px] text-slate-500 block">Nodes</span>
                  <span className="font-bold text-slate-800">{nodes.length}</span>
                </div>
                <div className="rounded bg-white p-2 border border-slate-200 shadow-2xs">
                  <span className="text-[10px] text-slate-500 block">Transitions</span>
                  <span className="font-bold text-slate-800">{edges.length}</span>
                </div>
                <div className="rounded bg-white p-2 border border-slate-200 shadow-2xs">
                  <span className="text-[10px] text-slate-500 block">Warnings</span>
                  <span className="font-bold text-amber-600">{warningIssues.length}</span>
                </div>
              </div>
            </div>

            {/* Actions */}
            <div className="flex items-center justify-end gap-2 pt-2 border-t border-slate-100">
              <button
                type="button"
                data-testid="cancel-publish-btn"
                onClick={onClose}
                className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                data-testid="confirm-publish-btn"
                disabled={isBlocked || isPublishing}
                onClick={handlePublish}
                className="rounded-lg bg-emerald-600 px-4 py-1.5 text-xs font-semibold text-white hover:bg-emerald-700 disabled:opacity-40 disabled:cursor-not-allowed shadow-xs"
              >
                {isPublishing ? "Publishing..." : "Confirm & Publish"}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
