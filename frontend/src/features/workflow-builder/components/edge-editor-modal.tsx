"use client";

import { useId, useState } from "react";
import type {
  EdgeData,
  EdgeTransitionType,
  ReworkConfig,
} from "../editor-types";
import type { BuilderEdge, BuilderNode } from "../types";
import { ConditionExpressionEditor } from "./condition-expression-editor";
import { ReworkEditor } from "./rework-editor";
import type { AstOperator } from "../utils/condition-compiler";
import {
  createDefaultReworkConfig,
  fromReworkPolicy,
  toReworkPolicy,
} from "../editor-types";

interface EdgeEditorModalProps {
  isOpen: boolean;
  edge: BuilderEdge;
  nodes: BuilderNode[];
  onSave: (updatedEdge: BuilderEdge) => void;
  onDelete?: (edgeId: string) => void;
  onClose: () => void;
  readOnly?: boolean;
}

export function EdgeEditorModal({
  isOpen,
  edge,
  nodes,
  onSave,
  onDelete,
  onClose,
  readOnly = false,
}: EdgeEditorModalProps) {
  const edgeData = (edge.data || {}) as EdgeData;
  const existingConfig =
    typeof edgeData.config === "object" &&
    edgeData.config !== null &&
    !Array.isArray(edgeData.config)
      ? (edgeData.config as Record<string, unknown>)
      : {};
  const persistedPolicy =
    typeof existingConfig.reworkPolicy === "object" &&
    existingConfig.reworkPolicy !== null &&
    !Array.isArray(existingConfig.reworkPolicy)
      ? (existingConfig.reworkPolicy as Record<string, unknown>)
      : undefined;
  const initialSourceHandle = String(edge.sourceHandle ?? "DEFAULT");
  const initialTransitionType: EdgeTransitionType =
    edgeData.transitionType ??
    (initialSourceHandle === "REVISION_REQUESTED" ? "REWORK" : "NORMAL");
  const initialReworkConfig =
    edgeData.reworkConfig ??
    (persistedPolicy
      ? fromReworkPolicy(
          edge.target,
          persistedPolicy,
          existingConfig.rollbackStrategy,
        )
      : initialTransitionType === "REWORK"
        ? createDefaultReworkConfig(edge.target)
        : undefined);

  const [label, setLabel] = useState<string>(String(edge.label ?? ""));
  const [sourceHandle, setSourceHandle] = useState<string>(initialSourceHandle);
  const [targetNodeId, setTargetNodeId] = useState<string>(edge.target);
  const [priority, setPriority] = useState<number>(edgeData.priority ?? 10);
  const [isDefault, setIsDefault] = useState<boolean>(
    edgeData.isDefault ?? false,
  );
  const [transitionType, setTransitionType] = useState<EdgeTransitionType>(
    initialTransitionType,
  );
  const [condition, setCondition] = useState<
    Record<string, unknown> | string | undefined
  >(edgeData.condition);
  const [reworkConfig, setReworkConfig] = useState<ReworkConfig | undefined>(
    initialReworkConfig,
  );

  // Keep the route fields visible when a rework edge is opened so users can
  // immediately verify which node the revision branch returns to.
  const [activeTab, setActiveTab] = useState<
    "general" | "condition" | "rework"
  >("general");
  const formHtmlId = useId();

  if (!isOpen) return null;

  const sourceNode = nodes.find((n) => n.id === edge.source);
  const availablePorts = sourceNode?.data.outputPorts || ["DEFAULT"];
  const isRevisionBranch = sourceHandle === "REVISION_REQUESTED";

  const handleSave = () => {
    if (readOnly) return;

    const cleanConfig = Object.fromEntries(
      Object.entries(existingConfig).filter(
        ([key]) => key !== "reworkPolicy" && key !== "rollbackStrategy",
      ),
    );
    const effectiveReworkConfig =
      reworkConfig ?? createDefaultReworkConfig(targetNodeId);
    const config =
      transitionType === "REWORK" || transitionType === "RETURN"
        ? {
            ...cleanConfig,
            reworkPolicy: toReworkPolicy(effectiveReworkConfig),
            rollbackStrategy: effectiveReworkConfig.rollbackStrategy,
          }
        : cleanConfig;

    // Destination strictly exists in edge.target, not in Node
    const updatedEdge: BuilderEdge = {
      ...edge,
      target: targetNodeId,
      sourceHandle: sourceHandle || undefined,
      label: label.trim() || undefined,
      data: {
        ...edge.data,
        priority,
        isDefault,
        transitionType,
        condition,
        reworkConfig:
          transitionType === "REWORK" || transitionType === "RETURN"
            ? effectiveReworkConfig
            : undefined,
        config,
      },
    };

    onSave(updatedEdge);
    onClose();
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      data-testid="edge-editor-modal"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-xs"
    >
      <div className="animate-scale-in w-full max-w-2xl space-y-4 rounded-xl bg-white p-6 shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-200 pb-3">
          <div>
            <h3 className="text-sm font-bold text-slate-900">
              Trình chỉnh sửa chuyển tiếp
            </h3>
          </div>
          <button
            type="button"
            data-testid="close-edge-modal-btn"
            onClick={onClose}
            className="rounded p-1 text-slate-400 hover:text-slate-600"
          >
            ✕
          </button>
        </div>

        {/* Tabs */}
        <div className="flex gap-1 border-b border-slate-200 pb-1">
          <button
            type="button"
            data-testid="edge-tab-general"
            onClick={() => setActiveTab("general")}
            className={`rounded px-3 py-1 text-xs font-semibold transition-colors ${
              activeTab === "general"
                ? "bg-blue-50 text-blue-700"
                : "text-slate-500 hover:text-slate-900"
            }`}
          >
            Chung & định tuyến
          </button>
          <button
            type="button"
            data-testid="edge-tab-condition"
            onClick={() => setActiveTab("condition")}
            className={`rounded px-3 py-1 text-xs font-semibold transition-colors ${
              activeTab === "condition"
                ? "bg-blue-50 text-blue-700"
                : "text-slate-500 hover:text-slate-900"
            }`}
          >
            Biểu thức điều kiện
          </button>
          {(transitionType === "REWORK" || transitionType === "RETURN") && (
            <button
              type="button"
              data-testid="edge-tab-rework"
              onClick={() => setActiveTab("rework")}
              className={`rounded px-3 py-1 text-xs font-semibold transition-colors ${
                activeTab === "rework"
                  ? "bg-amber-50 text-amber-700"
                  : "text-slate-500 hover:text-slate-900"
              }`}
            >
              Vòng lặp xử lý lại
            </button>
          )}
        </div>

        {/* General Tab */}
        {activeTab === "general" && (
          <div className="space-y-3 pt-1">
            {isRevisionBranch && (
              <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[11px] text-amber-900">
                <p className="font-semibold">Nhánh Yêu cầu bổ sung</p>
                <p className="mt-0.5">
                  Chọn bước phê duyệt hoặc kiểm tra cần quay lại. Hệ thống sẽ
                  tạo vòng lặp có giới hạn để người dùng bổ sung thông tin rồi
                  xử lý lại.
                </p>
              </div>
            )}
            <div className="grid grid-cols-2 gap-3">
              {/* Source Port Handle */}
              <div>
                <label
                  htmlFor={`${formHtmlId}-sourceHandle`}
                  className="mb-0.5 block text-[11px] font-semibold text-slate-700"
                >
                  Cổng nguồn *
                </label>
                <select
                  id={`${formHtmlId}-sourceHandle`}
                  data-testid="select-edge-source-handle"
                  disabled={readOnly}
                  value={sourceHandle}
                  onChange={(e) => {
                    const nextSource = e.target.value;
                    setSourceHandle(nextSource);
                    if (nextSource === "REVISION_REQUESTED") {
                      setTransitionType("REWORK");
                      setReworkConfig(
                        (current) =>
                          current ?? createDefaultReworkConfig(targetNodeId),
                      );
                    }
                  }}
                  className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 font-mono text-xs disabled:bg-slate-100"
                >
                  {availablePorts.map((p) => (
                    <option key={p} value={p}>
                      {p}
                    </option>
                  ))}
                </select>
              </div>

              {/* Target Node Destination */}
              <div>
                <label
                  htmlFor={`${formHtmlId}-targetNodeId`}
                  className="mb-0.5 block text-[11px] font-semibold text-slate-700"
                >
                  Bước đích *
                </label>
                <select
                  id={`${formHtmlId}-targetNodeId`}
                  data-testid="select-edge-target-node"
                  disabled={readOnly}
                  value={targetNodeId}
                  onChange={(e) => {
                    const nextTarget = e.target.value;
                    setTargetNodeId(nextTarget);
                    setReworkConfig((current) =>
                      current
                        ? { ...current, targetStepId: nextTarget }
                        : current,
                    );
                  }}
                  className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs font-semibold disabled:bg-slate-100"
                >
                  {nodes
                    .filter(
                      (n) =>
                        n.id !== edge.source && n.data.nodeType !== "START",
                    )
                    .map((n) => (
                      <option key={n.id} value={n.id}>
                        {n.data.label} ({n.data.key})
                      </option>
                    ))}
                </select>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              {/* Transition Type */}
              <div>
                <label
                  htmlFor={`${formHtmlId}-transitionType`}
                  className="mb-0.5 block text-[11px] font-semibold text-slate-700"
                >
                  Loại chuyển tiếp *
                </label>
                <select
                  id={`${formHtmlId}-transitionType`}
                  data-testid="select-edge-transition-type"
                  disabled={readOnly}
                  value={transitionType}
                  onChange={(e) => {
                    const nextType = e.target.value as EdgeTransitionType;
                    setTransitionType(nextType);
                    if (
                      (nextType === "REWORK" || nextType === "RETURN") &&
                      !reworkConfig
                    ) {
                      setReworkConfig(createDefaultReworkConfig(targetNodeId));
                    }
                  }}
                  className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs font-bold text-slate-800 disabled:bg-slate-100"
                >
                  <option value="NORMAL">NORMAL (Luồng xử lý tiếp theo)</option>
                  <option value="REWORK">REWORK (Quay lại bước trước)</option>
                  <option value="RETURN">
                    RETURN (Quay về từ quy trình con)
                  </option>
                </select>
              </div>

              {/* Priority */}
              <div>
                <label
                  htmlFor={`${formHtmlId}-priority`}
                  className="mb-0.5 block text-[11px] font-semibold text-slate-700"
                >
                  Độ ưu tiên (thứ tự đánh giá)
                </label>
                <input
                  id={`${formHtmlId}-priority`}
                  type="number"
                  min="1"
                  max="1000"
                  data-testid="input-edge-priority"
                  disabled={readOnly}
                  value={priority}
                  onChange={(e) =>
                    setPriority(parseInt(e.target.value || "10", 10))
                  }
                  className="w-full rounded border border-slate-300 px-2.5 py-1.5 text-xs disabled:bg-slate-100"
                />
              </div>
            </div>

            <div>
              <label
                htmlFor={`${formHtmlId}-edgeLabel`}
                className="mb-0.5 block text-[11px] font-semibold text-slate-700"
              >
                Nhãn chuyển tiếp
              </label>
              <input
                id={`${formHtmlId}-edgeLabel`}
                type="text"
                data-testid="input-edge-label"
                disabled={readOnly}
                value={label}
                placeholder="Ví dụ: Đã duyệt / Trên 5.000 USD"
                onChange={(e) => setLabel(e.target.value)}
                className="w-full rounded border border-slate-300 px-2.5 py-1.5 text-xs disabled:bg-slate-100"
              />
            </div>

            <div className="pt-1">
              <label className="flex cursor-pointer items-center gap-2 text-xs font-semibold text-slate-700">
                <input
                  type="checkbox"
                  data-testid="checkbox-edge-is-default"
                  disabled={readOnly}
                  checked={isDefault}
                  onChange={(e) => setIsDefault(e.target.checked)}
                  className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                />
                <span>
                  Chuyển tiếp mặc định (kích hoạt khi không có điều kiện phù
                  hợp)
                </span>
              </label>
            </div>
          </div>
        )}

        {/* Condition Tab */}
        {activeTab === "condition" && (
          <div className="pt-1">
            <ConditionExpressionEditor
              value={condition}
              onChange={(compiled: AstOperator) => setCondition(compiled)}
              readOnly={readOnly}
            />
          </div>
        )}

        {/* Rework Tab */}
        {activeTab === "rework" &&
          (transitionType === "REWORK" || transitionType === "RETURN") && (
            <div className="pt-1">
              <ReworkEditor
                value={reworkConfig}
                onChange={(config) => {
                  setReworkConfig(config);
                  setTargetNodeId(config.targetStepId);
                }}
                availableNodes={nodes.filter(
                  (n) => n.id !== edge.source && n.data.nodeType !== "START",
                )}
                availableExhaustionPorts={sourceNode?.data.outputPorts}
                readOnly={readOnly}
              />
            </div>
          )}

        {/* Footer Actions */}
        <div className="flex items-center justify-between border-t border-slate-200 pt-3">
          <div>
            {!readOnly && onDelete && (
              <button
                type="button"
                data-testid="btn-delete-edge"
                onClick={() => {
                  onDelete(edge.id);
                  onClose();
                }}
                className="text-xs font-semibold text-rose-600 hover:text-rose-800"
              >
                Xóa chuyển tiếp
              </button>
            )}
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              data-testid="btn-cancel-edge"
              onClick={onClose}
              className="rounded border border-slate-300 px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
            >
              Hủy
            </button>
            {!readOnly && (
              <button
                type="button"
                data-testid="btn-save-edge"
                onClick={handleSave}
                className="rounded bg-blue-600 px-4 py-1.5 text-xs font-semibold text-white shadow-xs hover:bg-blue-700"
              >
                Lưu thay đổi
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
