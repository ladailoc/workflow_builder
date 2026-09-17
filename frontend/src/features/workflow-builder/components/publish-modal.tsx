"use client";

import { useMemo, useState } from "react";
import { ApiRequestError } from "@/shared/api/client";
import type {
  BuilderEdge,
  BuilderNode,
  ValidationIssue,
  WorkflowVersionDto,
} from "../types";
import { validateWorkflowGraph } from "../validator";
import { getValidationIssuePresentation } from "../validation-copy";

interface PublishModalProps {
  isOpen: boolean;
  version: WorkflowVersionDto;
  nodes: BuilderNode[];
  edges: BuilderEdge[];
  onConfirmPublish: (acknowledgedWarnings?: readonly string[]) => Promise<void>;
  onValidate?: () => Promise<ValidationIssue[]>;
  onClose: () => void;
  onSelectIssue?: (nodeId?: string, field?: string) => void;
}

function publishGraphSignature(
  nodes: BuilderNode[],
  edges: BuilderEdge[],
): string {
  return JSON.stringify({
    nodes: nodes.map((node) => ({
      id: node.id,
      position: node.position,
      data: node.data,
    })),
    edges: edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      sourceHandle: edge.sourceHandle,
      targetHandle: edge.targetHandle,
      data: edge.data,
    })),
  });
}

export function PublishModal({
  isOpen,
  version,
  nodes,
  edges,
  onConfirmPublish,
  onValidate,
  onClose,
  onSelectIssue,
}: PublishModalProps) {
  const [serverIssues, setServerIssues] = useState<ValidationIssue[]>([]);
  const [acknowledgedWarningCodes, setAcknowledgedWarningCodes] = useState<
    string[]
  >([]);
  const [acknowledgedGraphSignature, setAcknowledgedGraphSignature] = useState<
    string | null
  >(null);
  const [isPublishing, setIsPublishing] = useState(false);
  const [publishSuccess, setPublishSuccess] = useState(false);
  const [publishError, setPublishError] = useState<string | null>(null);
  const [serverValidationSignature, setServerValidationSignature] = useState<
    string | null
  >(null);

  const graphSignature = useMemo(
    () => publishGraphSignature(nodes, edges),
    [nodes, edges],
  );
  const activeServerIssues = useMemo(
    () => (serverValidationSignature === graphSignature ? serverIssues : []),
    [graphSignature, serverIssues, serverValidationSignature],
  );

  // Enforce validation right before publish
  const validationIssues: ValidationIssue[] = useMemo(
    () => [...validateWorkflowGraph(nodes, edges), ...activeServerIssues],
    [nodes, edges, activeServerIssues],
  );

  const errorIssues = validationIssues.filter((i) => i.severity === "ERROR");
  const warningIssues = validationIssues.filter(
    (i) => i.severity === "WARNING",
  );
  const acknowledgementIssues = activeServerIssues.filter(
    (issue) => issue.severity === "WARNING" && issue.acknowledgementRequired,
  );
  const acknowledgementCodes = Array.from(
    new Set(
      acknowledgementIssues
        .map((issue) => issue.code)
        .filter((code): code is string => Boolean(code)),
    ),
  );
  const hasValidationErrors = errorIssues.length > 0;
  const hasAcknowledgedWarnings =
    acknowledgementCodes.length === 0 ||
    (acknowledgedGraphSignature === graphSignature &&
      acknowledgementCodes.every((code) =>
        acknowledgedWarningCodes.includes(code),
      ));
  const needsWarningAcknowledgement =
    acknowledgementCodes.length > 0 && !hasAcknowledgedWarnings;
  const isBlocked = hasValidationErrors || needsWarningAcknowledgement;

  function getPublishErrorMessage(error: unknown): string {
    if (error instanceof ApiRequestError) {
      switch (error.code) {
        case "WORKFLOW_VALIDATION_FAILED":
          return "Quy trình chưa đạt điều kiện phát hành. Hãy kiểm tra và sửa các lỗi được đánh dấu.";
        case "WORKFLOW_VALIDATION_ACK_REQUIRED":
          return "Quy trình còn cảnh báo cần được xác nhận trước khi phát hành.";
        case "WORKFLOW_DRAFT_REVISION_CONFLICT":
          return "Bản nháp đã thay đổi ở nơi khác. Hãy tải lại trang rồi thử lại.";
        default:
          return "Không thể phát hành phiên bản này. Hãy kiểm tra lại quy trình và thử lại.";
      }
    }
    if (error instanceof Error && error.message) return error.message;
    return "Không thể phát hành phiên bản này. Hãy thử lại.";
  }

  if (!isOpen) return null;

  const handlePublish = async () => {
    if (isBlocked || isPublishing) return;
    setIsPublishing(true);
    setPublishError(null);
    try {
      let latestAcknowledgementCodes: string[] = [];
      if (onValidate) {
        const latestIssues = await onValidate();
        const latestErrors = latestIssues.filter(
          (issue) => issue.severity === "ERROR",
        );
        latestAcknowledgementCodes = Array.from(
          new Set(
            latestIssues
              .filter(
                (issue) =>
                  issue.severity === "WARNING" && issue.acknowledgementRequired,
              )
              .map((issue) => issue.code)
              .filter((code): code is string => Boolean(code)),
          ),
        );
        setServerIssues(latestIssues);
        setServerValidationSignature(graphSignature);
        setAcknowledgedWarningCodes((current) =>
          current.filter((code) => latestAcknowledgementCodes.includes(code)),
        );

        const latestWarningsAcknowledged =
          latestAcknowledgementCodes.length === 0 ||
          (acknowledgedGraphSignature === graphSignature &&
            latestAcknowledgementCodes.every((code) =>
              acknowledgedWarningCodes.includes(code),
            ));
        if (latestErrors.length > 0 || !latestWarningsAcknowledged) return;
      }

      await onConfirmPublish(
        latestAcknowledgementCodes.length > 0
          ? latestAcknowledgementCodes
          : undefined,
      );
      setPublishSuccess(true);
      setTimeout(() => {
        onClose();
      }, 1500);
    } catch (error) {
      setPublishError(getPublishErrorMessage(error));
    } finally {
      setIsPublishing(false);
    }
  };

  const handleWarningAcknowledgementChange = (checked: boolean) => {
    setAcknowledgedWarningCodes(checked ? acknowledgementCodes : []);
    setAcknowledgedGraphSignature(checked ? graphSignature : null);
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      data-testid="publish-modal"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-xs"
    >
      <div className="animate-scale-in w-full max-w-lg space-y-4 rounded-xl bg-white p-6 shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-200 pb-3">
          <div>
            <h3 className="text-sm font-bold text-slate-900">
              Phát hành phiên bản quy trình #{version.versionNo}
            </h3>
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
            className="space-y-1 rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-center text-xs font-semibold text-emerald-800"
          >
            <p className="text-sm font-bold">✓ Phát hành thành công!</p>
            <p className="text-[11px] text-emerald-700">
              Phiên bản #{version.versionNo} đã được phát hành và đang hoạt
              động.
            </p>
          </div>
        ) : (
          <>
            {/* Publication Pre-Validation Gate */}
            {hasValidationErrors ? (
              <div
                data-testid="publish-blocked-alert"
                className="space-y-2 rounded-lg border border-rose-300 bg-rose-50 p-3.5 text-xs text-rose-900"
              >
                <div className="flex items-center gap-2 font-bold text-rose-800">
                  <span className="flex h-5 w-5 items-center justify-center rounded-full bg-rose-200 font-mono text-[10px] text-rose-800">
                    !
                  </span>
                  <span>
                    Không thể phát hành: phát hiện {errorIssues.length} lỗi
                  </span>
                </div>
                <div className="max-h-36 space-y-1.5 overflow-y-auto pt-1">
                  {errorIssues.map((err) =>
                    (() => {
                      const presentation = getValidationIssuePresentation(err);
                      return (
                        <div
                          key={err.id}
                          data-testid={`publish-error-item-${err.id}`}
                          className="flex items-start justify-between gap-3 rounded border border-rose-200 bg-white p-2 text-[11px]"
                        >
                          <span>
                            <span className="block font-medium text-slate-800">
                              {presentation.message}
                            </span>
                            <span className="mt-0.5 block text-[10px] text-slate-500">
                              <span className="font-semibold">Cách sửa:</span>{" "}
                              {presentation.suggestion}
                            </span>
                          </span>
                          {err.nodeId && onSelectIssue && (
                            <button
                              type="button"
                              onClick={() => {
                                onSelectIssue(err.nodeId, err.field);
                                onClose();
                              }}
                              className="shrink-0 text-blue-600 hover:underline"
                            >
                              Đi tới bước →
                            </button>
                          )}
                        </div>
                      );
                    })(),
                  )}
                </div>
              </div>
            ) : needsWarningAcknowledgement ? (
              <div
                data-testid="publish-warning-ack-alert"
                className="space-y-2 rounded-lg border border-amber-300 bg-amber-50 p-3.5 text-xs text-amber-950"
              >
                <div className="flex items-center gap-2 font-bold text-amber-900">
                  <span className="flex h-5 w-5 items-center justify-center rounded-full bg-amber-200 font-mono text-[10px] text-amber-900">
                    !
                  </span>
                  <span>
                    Còn {acknowledgementIssues.length} cảnh báo cần xác nhận
                  </span>
                </div>
                <div className="max-h-36 space-y-1.5 overflow-y-auto pt-1">
                  {acknowledgementIssues.map((issue) => {
                    const presentation = getValidationIssuePresentation(issue);
                    return (
                      <div
                        key={issue.id}
                        className="rounded border border-amber-200 bg-white p-2 text-[11px]"
                      >
                        <span className="block font-medium text-slate-800">
                          {presentation.message}
                        </span>
                        <span className="mt-0.5 block text-[10px] text-slate-500">
                          <span className="font-semibold">Gợi ý:</span>{" "}
                          {presentation.suggestion}
                        </span>
                      </div>
                    );
                  })}
                </div>
                <label className="flex cursor-pointer items-start gap-2 rounded border border-amber-200 bg-white p-2 font-medium text-amber-900">
                  <input
                    type="checkbox"
                    data-testid="publish-warning-ack-checkbox"
                    checked={hasAcknowledgedWarnings}
                    onChange={(event) =>
                      handleWarningAcknowledgementChange(event.target.checked)
                    }
                    className="mt-0.5 accent-amber-600"
                  />
                  <span>
                    Tôi đã xem và xác nhận các cảnh báo trên để tiếp tục phát
                    hành.
                  </span>
                </label>
              </div>
            ) : (
              <div
                data-testid="publish-validation-clean"
                className="flex items-center gap-2 rounded-lg border border-emerald-200 bg-emerald-50/60 p-3 text-xs text-emerald-900"
              >
                <span className="font-bold text-emerald-700">
                  ✓ Đã vượt qua kiểm tra:
                </span>
                <span>
                  Tất cả quy tắc kiểm tra sơ đồ đều đạt, không có lỗi.
                </span>
              </div>
            )}

            {publishError && (
              <div
                role="alert"
                data-testid="publish-error-alert"
                className="rounded-lg border border-rose-300 bg-rose-50 p-3 text-xs font-medium text-rose-900"
              >
                {publishError}
              </div>
            )}

            {/* Publication Summary */}
            <div className="space-y-2 rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs">
              <span className="block font-bold text-slate-800">
                Tóm tắt phát hành
              </span>
              <div className="grid grid-cols-3 gap-2">
                <div className="rounded border border-slate-200 bg-white p-2 shadow-2xs">
                  <span className="block text-[10px] text-slate-500">Bước</span>
                  <span className="font-bold text-slate-800">
                    {nodes.length}
                  </span>
                </div>
                <div className="rounded border border-slate-200 bg-white p-2 shadow-2xs">
                  <span className="block text-[10px] text-slate-500">
                    Chuyển tiếp
                  </span>
                  <span className="font-bold text-slate-800">
                    {edges.length}
                  </span>
                </div>
                <div className="rounded border border-slate-200 bg-white p-2 shadow-2xs">
                  <span className="block text-[10px] text-slate-500">
                    Cảnh báo
                  </span>
                  <span className="font-bold text-amber-600">
                    {warningIssues.length}
                  </span>
                </div>
              </div>
            </div>

            {/* Actions */}
            <div className="flex items-center justify-end gap-2 border-t border-slate-100 pt-2">
              <button
                type="button"
                data-testid="cancel-publish-btn"
                onClick={onClose}
                className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
              >
                Hủy
              </button>
              <button
                type="button"
                data-testid="confirm-publish-btn"
                disabled={isBlocked || isPublishing}
                onClick={handlePublish}
                className="rounded-lg bg-emerald-600 px-4 py-1.5 text-xs font-semibold text-white shadow-xs hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {isPublishing ? "Đang phát hành…" : "Xác nhận & phát hành"}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
