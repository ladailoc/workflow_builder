"use client";

import { useId } from "react";
import { DynamicNodeProperties } from "./dynamic-node-properties";
import type { BuilderNode } from "../types";

interface PropertiesPanelProps {
  selectedNode: BuilderNode | null;
  onUpdateNode: (nodeId: string, updates: Partial<BuilderNode["data"]>) => void;
  onDeleteNode: (nodeId: string) => void;
  readOnly?: boolean;
}

export function PropertiesPanel({
  selectedNode,
  onUpdateNode,
  onDeleteNode,
  readOnly = false,
}: PropertiesPanelProps) {
  const nodeKeyId = useId();
  const nodeLabelId = useId();

  if (!selectedNode) {
    return (
      <aside
        data-testid="properties-panel-empty"
        className="flex h-full w-80 flex-col items-center justify-center border-l border-slate-200 bg-white p-6 text-center"
      >
        <svg
          className="h-10 w-10 text-slate-300"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M15 15l-2 5L9 9l11 4-5 2zm0 0l5 5M7.188 2.239l.777 2.897M5.136 7.965l-2.898-.777M13.95 4.05l-2.122 2.122m-5.657 5.656l-2.12 2.122"
          />
        </svg>
        <p className="mt-3 text-xs font-semibold text-slate-700">
          No Node Selected
        </p>
        <p className="mt-1 text-[11px] text-slate-400">
          Click on any node on the canvas to configure its properties and ports.
        </p>
      </aside>
    );
  }

  const { data } = selectedNode;

  return (
    <aside
      data-testid="properties-panel"
      className="flex h-full w-80 flex-col border-l border-slate-200 bg-white"
    >
      <div className="flex items-center justify-between border-b border-slate-200 p-4">
        <div>
          <h2 className="text-sm font-bold text-slate-900">
            Node Properties
          </h2>
          <span className="rounded bg-blue-50 px-1.5 py-0.2 text-[10px] font-semibold text-blue-700 uppercase">
            {data.nodeType}
          </span>
        </div>

        {!readOnly && data.nodeType !== "START" && (
          <button
            type="button"
            data-testid="delete-node-button"
            onClick={() => onDeleteNode(selectedNode.id)}
            className="rounded p-1.5 text-slate-400 hover:bg-rose-50 hover:text-rose-600 transition-colors"
            title="Delete Node"
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
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {/* Node Key */}
        <div className="space-y-1">
          <label htmlFor={nodeKeyId} className="text-xs font-semibold text-slate-700">
            Node Key
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
          <label htmlFor={nodeLabelId} className="text-xs font-semibold text-slate-700">
            Display Label
          </label>
          <input
            id={nodeLabelId}
            type="text"
            data-testid="prop-node-label"
            disabled={readOnly}
            value={data.label}
            onChange={(e) =>
              onUpdateNode(selectedNode.id, { label: e.target.value })
            }
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs text-slate-800 focus:border-blue-500 focus:outline-none disabled:bg-slate-100"
          />
        </div>

        {/* Output Ports Overview */}
        <div className="space-y-1 border-t border-slate-100 pt-3">
          <span className="text-xs font-semibold text-slate-700">
            Configured Output Ports
          </span>
          {data.outputPorts.length === 0 ? (
            <p className="text-[11px] text-slate-400 italic">No outgoing ports (Terminal Node).</p>
          ) : (
            <div className="flex flex-wrap gap-1 mt-1">
              {data.outputPorts.map((port) => (
                <span
                  key={port}
                  data-testid={`port-badge-${port}`}
                  className="rounded-md bg-slate-100 px-2 py-0.5 font-mono text-[10px] font-semibold text-slate-700"
                >
                  {port}
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
  );
}
