"use client";

import { Handle, Position } from "@xyflow/react";
import { getNodeManifest } from "../manifest";
import type { NodeData } from "../types";

interface CustomWorkflowNodeProps {
  data: NodeData;
  selected?: boolean;
}

export function CustomWorkflowNode({
  data,
  selected = false,
}: CustomWorkflowNodeProps) {
  const manifest = getNodeManifest(data.nodeType);
  const isStart = data.nodeType === "START";
  const isEnd = data.nodeType === "END";

  return (
    <div
      data-testid={`canvas-node-${data.key}`}
      className={`w-52 rounded-xl border bg-white shadow-sm transition-all ${
        selected
          ? "border-blue-500 ring-2 ring-blue-500/20 shadow-md"
          : "border-slate-200 hover:border-slate-300"
      }`}
    >
      {/* Target input handle (except for START) */}
      {!isStart && (
        <Handle
          type="target"
          position={Position.Top}
          id="target-input"
          className="!h-3 !w-3 !rounded-full !border-2 !border-white !bg-slate-400 hover:!bg-blue-600"
        />
      )}

      {/* Node Header */}
      <div
        className={`flex items-center justify-between rounded-t-[11px] px-3 py-2 ${
          manifest?.color || "bg-slate-700 text-white"
        }`}
      >
        <span className="text-xs font-bold tracking-tight truncate">
          {data.label}
        </span>
        <span className="rounded bg-black/20 px-1.5 py-0.2 text-[9px] font-semibold uppercase tracking-wider">
          {data.nodeType}
        </span>
      </div>

      {/* Node Body */}
      <div className="p-3 space-y-2">
        <p className="text-[10px] font-mono text-slate-400 truncate">
          key: {data.key}
        </p>

        {/* Output Ports (Source Handles) */}
        {!isEnd && data.outputPorts.length > 0 && (
          <div className="space-y-1.5 border-t border-slate-100 pt-2">
            <span className="text-[10px] font-semibold text-slate-400 uppercase tracking-wider block">
              Outcomes
            </span>
            <div className="space-y-1">
              {data.outputPorts.map((port) => (
                <div
                  key={port}
                  className="relative flex items-center justify-between rounded bg-slate-50 px-2 py-1 text-[11px] font-medium text-slate-700"
                >
                  <span>{port}</span>
                  <Handle
                    type="source"
                    position={Position.Right}
                    id={port}
                    className="!static !transform-none !h-2.5 !w-2.5 !rounded-full !border-2 !border-white !bg-blue-600 hover:!bg-blue-800"
                  />
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
