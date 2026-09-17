"use client";

import { Handle, Position } from "@xyflow/react";
import {
  formatPortLabel,
  getNodeDisplayName,
  getNodeManifest,
} from "../manifest";
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
      className={`w-52 overflow-hidden rounded-xl border bg-white shadow-sm transition-all ${
        selected
          ? "border-blue-500 shadow-md ring-2 ring-blue-500/20"
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
        className={`flex items-center justify-between px-3 py-2 ${manifest?.color || "bg-slate-700 text-white"} ${
          isEnd || data.outputPorts.length === 0 ? "rounded-b-[11px]" : ""
        }`}
      >
        <span className="truncate text-xs font-bold tracking-tight">
          {getNodeDisplayName(data.nodeType, data.label)}
        </span>
        <span className="py-0.2 rounded bg-black/20 px-1.5 text-[9px] font-semibold tracking-wider uppercase">
          {data.nodeType}
        </span>
      </div>

      {/* Output Ports (Source Handles) */}
      {!isEnd && data.outputPorts.length > 0 ? (
        <div className="space-y-1.5 bg-white p-2.5">
          <div className="space-y-1">
            {data.outputPorts.map((port) => (
              <div
                key={port}
                title={
                  port === "REVISION_REQUESTED"
                    ? "Kéo cổng này tới bước muốn xử lý lại"
                    : `Kéo cổng ${formatPortLabel(port)} tới bước tiếp theo`
                }
                className={`relative flex items-center justify-between rounded-md px-2 py-1 text-[11px] font-medium ${
                  port === "REVISION_REQUESTED"
                    ? "bg-amber-50 text-amber-800"
                    : "bg-slate-50 text-slate-700"
                }`}
              >
                <span>{formatPortLabel(port)}</span>
                <Handle
                  type="source"
                  position={Position.Right}
                  id={port}
                  className={`!static !h-2.5 !w-2.5 !transform-none !rounded-full !border-2 !border-white ${
                    port === "REVISION_REQUESTED"
                      ? "!bg-amber-500 hover:!bg-amber-700"
                      : "!bg-blue-600 hover:!bg-blue-800"
                  }`}
                />
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}
