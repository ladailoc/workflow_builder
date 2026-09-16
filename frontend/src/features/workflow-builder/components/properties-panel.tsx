"use client";

import { useId } from "react";
import { DynamicNodeProperties } from "./dynamic-node-properties";
import {
  formatPortLabel,
  getNodeDisplayName,
} from "../manifest";
import type { BuilderNode } from "../types";

interface PropertiesPanelProps {
  selectedNode: BuilderNode | null;
  onUpdateNode: (nodeId: string, updates: Partial<BuilderNode["data"]>) => void;
  onDeleteNode: (nodeId: string) => void;
  onClose?: () => void;
  readOnly?: boolean;
}

export function PropertiesPanel({
  selectedNode,
  onUpdateNode,
  onDeleteNode,
  onClose,
  readOnly = false,
}: PropertiesPanelProps) {
  const nodeKeyId = useId();
  const nodeLabelId = useId();

  if (!selectedNode) {
    return null;
  }

  const { data } = selectedNode;
  return (
    <div
      role="dialog"
      aria-modal="true"
      data-testid="properties-modal"
      className="fixed inset-0 z-[60] flex items-center justify-center bg-slate-950/30 p-4 backdrop-blur-[1px]"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose?.();
      }}
    >
      <aside
        data-testid="properties-panel"
        className="flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-2xl"
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-200 p-5">
          <div>
            <h2 className="text-sm font-bold text-slate-900">Thuộc tính bước</h2>
            <span className="mt-1 inline-flex rounded bg-blue-50 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-blue-700">
              {getNodeDisplayName(data.nodeType, data.label)}
            </span>
          </div>

          <div className="flex items-center gap-1">
            {!readOnly && data.nodeType !== "START" && (
              <button
                type="button"
                data-testid="delete-node-button"
                onClick={() => onDeleteNode(selectedNode.id)}
                className="rounded-lg p-2 text-slate-400 transition-colors hover:bg-rose-50 hover:text-rose-600"
                title="Xóa bước"
              >
                <svg
                  className="h-4 w-4"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                  />
                </svg>
              </button>
            )}
            <button
              type="button"
              data-testid="close-properties-modal"
              onClick={() => onClose?.()}
              className="rounded-lg p-2 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700"
              title="Đóng cấu hình"
              aria-label="Đóng cấu hình bước"
            >
              ✕
            </button>
          </div>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto p-5">
        {/* Node Key */}
        <div className="space-y-1">
          <label
            htmlFor={nodeKeyId}
            className="text-xs font-semibold text-slate-700"
          >
            Khóa bước
          </label>
          <input
            id={nodeKeyId}
            type="text"
            data-testid="prop-node-key"
            disabled={readOnly}
            value={data.key}
            onChange={(e) =>
              onUpdateNode(selectedNode.id, { key: e.target.value })
            }
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-xs text-slate-800 focus:border-blue-500 focus:outline-none disabled:bg-slate-100"
          />
        </div>

        {/* Node Label */}
        <div className="space-y-1">
          <label
            htmlFor={nodeLabelId}
            className="text-xs font-semibold text-slate-700"
          >
            Nhãn hiển thị
          </label>
          <input
            id={nodeLabelId}
            type="text"
            data-testid="prop-node-label"
            disabled={readOnly}
            value={getNodeDisplayName(data.nodeType, data.label)}
            onChange={(e) =>
              onUpdateNode(selectedNode.id, { label: e.target.value })
            }
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs text-slate-800 focus:border-blue-500 focus:outline-none disabled:bg-slate-100"
          />
        </div>

        {/* Output Ports Overview */}
        <div className="space-y-1 border-t border-slate-100 pt-3">
          <span className="text-xs font-semibold text-slate-700">
            Các cổng đầu ra
          </span>
          {data.outputPorts.length === 0 ? (
            <p className="text-[11px] text-slate-400 italic">
              Không có cổng đầu ra (bước kết thúc).
            </p>
          ) : (
            <div className="mt-1 flex flex-wrap gap-1">
              {data.outputPorts.map((port) => (
                <span
                  key={port}
                  data-testid={`port-badge-${port}`}
                  className="rounded-md bg-slate-100 px-2 py-0.5 font-mono text-[10px] font-semibold text-slate-700"
                >
                  {formatPortLabel(port)}
                </span>
              ))}
            </div>
          )}
        </div>

        {/* Dynamic Schema-Driven Configuration Panel */}
        <div className="border-t border-slate-100 pt-3">
          <DynamicNodeProperties
            node={selectedNode}
            readOnly={readOnly}
            onUpdateConfig={(newConfig) =>
              onUpdateNode(selectedNode.id, { config: newConfig })
            }
          />
        </div>
        </div>
      </aside>
    </div>
  );
}
