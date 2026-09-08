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

  const [label, setLabel] = useState<string>(String(edge.label ?? ""));
  const [sourceHandle, setSourceHandle] = useState<string>(
    String(edge.sourceHandle ?? "DEFAULT"),
  );
  const [targetNodeId, setTargetNodeId] = useState<string>(edge.target);
  const [priority, setPriority] = useState<number>(edgeData.priority ?? 10);
  const [isDefault, setIsDefault] = useState<boolean>(edgeData.isDefault ?? false);
  const [transitionType, setTransitionType] = useState<EdgeTransitionType>(
    edgeData.transitionType ?? "NORMAL",
  );
  const [condition, setCondition] = useState<Record<string, unknown> | string | undefined>(
    edgeData.condition,
  );
  const [reworkConfig, setReworkConfig] = useState<ReworkConfig | undefined>(
    edgeData.reworkConfig,
  );

  const [activeTab, setActiveTab] = useState<"general" | "condition" | "rework">("general");
  const formHtmlId = useId();

  if (!isOpen) return null;

  const sourceNode = nodes.find((n) => n.id === edge.source);
  const availablePorts = sourceNode?.data.outputPorts || ["DEFAULT"];

  const handleSave = () => {
    if (readOnly) return;

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
        reworkConfig: transitionType === "REWORK" ? reworkConfig : undefined,
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
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-xs p-4"
    >
      <div className="w-full max-w-2xl rounded-xl bg-white p-6 shadow-2xl space-y-4 animate-scale-in">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-200 pb-3">
          <div>
            <h3 className="text-sm font-bold text-slate-900">
              Edge Transition Editor
            </h3>
            <p className="text-xs text-slate-500">
              Configure routing rules, transition priority, and condition semantics.
            </p>
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
        <div className="flex border-b border-slate-200 gap-1 pb-1">
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
            General & Routing
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
            Condition Expression
          </button>
          {transitionType === "REWORK" && (
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
              Rework Loop
            </button>
          )}
        </div>

        {/* General Tab */}
        {activeTab === "general" && (
          <div className="space-y-3 pt-1">
            <div className="grid grid-cols-2 gap-3">
              {/* Source Port Handle */}
              <div>
                <label
                  htmlFor={`${formHtmlId}-sourceHandle`}
                  className="text-[11px] font-semibold text-slate-700 block mb-0.5"
                >
                  Source Port Handle *
                </label>
                <select
                  id={`${formHtmlId}-sourceHandle`}
                  data-testid="select-edge-source-handle"
                  disabled={readOnly}
                  value={sourceHandle}
                  onChange={(e) => setSourceHandle(e.target.value)}
                  className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100 font-mono"
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
                  className="text-[11px] font-semibold text-slate-700 block mb-0.5"
                >
                  Target Node Destination *
                </label>
                <select
                  id={`${formHtmlId}-targetNodeId`}
                  data-testid="select-edge-target-node"
                  disabled={readOnly}
                  value={targetNodeId}
                  onChange={(e) => setTargetNodeId(e.target.value)}
                  className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100 font-semibold"
                >
                  {nodes
                    .filter((n) => n.id !== edge.source && n.data.nodeType !== "START")
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
                  className="text-[11px] font-semibold text-slate-700 block mb-0.5"
                >
                  Transition Type *
                </label>
                <select
                  id={`${formHtmlId}-transitionType`}
                  data-testid="select-edge-transition-type"
                  disabled={readOnly}
                  value={transitionType}
                  onChange={(e) => {
                    const nextType = e.target.value as EdgeTransitionType;
                    setTransitionType(nextType);
                    if (nextType === "REWORK" && !reworkConfig) {
                      setReworkConfig({
                        targetStepId: targetNodeId,
                        maxIterations: 3,
                        exhaustionBehavior: "FAIL_EVENT",
                        rollbackStrategy: "KEEP_CURRENT",
                        multiInstanceScope: "CURRENT_ITEM",
                      });
                    }
                  }}
                  className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100 font-bold text-slate-800"
                >
                  <option value="NORMAL">NORMAL (Forward execution flow)</option>
                  <option value="REWORK">REWORK (Loopback revision transition)</option>
                  <option value="RETURN">RETURN (Return from sub-process)</option>
                </select>
              </div>

              {/* Priority */}
              <div>
                <label
                  htmlFor={`${formHtmlId}-priority`}
                  className="text-[11px] font-semibold text-slate-700 block mb-0.5"
                >
                  Priority (Order of Evaluation)
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
                className="text-[11px] font-semibold text-slate-700 block mb-0.5"
              >
                Transition Label
              </label>
              <input
                id={`${formHtmlId}-edgeLabel`}
                type="text"
                data-testid="input-edge-label"
                disabled={readOnly}
                value={label}
                placeholder="e.g. Approved / Over 5000 USD"
                onChange={(e) => setLabel(e.target.value)}
                className="w-full rounded border border-slate-300 px-2.5 py-1.5 text-xs disabled:bg-slate-100"
              />
            </div>

            <div className="pt-1">
              <label className="flex items-center gap-2 cursor-pointer text-xs font-semibold text-slate-700">
                <input
                  type="checkbox"
                  data-testid="checkbox-edge-is-default"
                  disabled={readOnly}
                  checked={isDefault}
                  onChange={(e) => setIsDefault(e.target.checked)}
                  className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                />
                <span>Default Fallback Transition (Triggers when no condition matches)</span>
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
        {activeTab === "rework" && transitionType === "REWORK" && (
          <div className="pt-1">
            <ReworkEditor
              value={reworkConfig}
              onChange={setReworkConfig}
              availableNodes={nodes}
              readOnly={readOnly}
            />
          </div>
        )}

        {/* Footer Actions */}
        <div className="flex items-center justify-between pt-3 border-t border-slate-200">
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
                Delete Transition
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
              Cancel
            </button>
            {!readOnly && (
              <button
                type="button"
                data-testid="btn-save-edge"
                onClick={handleSave}
                className="rounded bg-blue-600 px-4 py-1.5 text-xs font-semibold text-white hover:bg-blue-700 shadow-xs"
              >
                Save Changes
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
