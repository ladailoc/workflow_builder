"use client";

import { useState } from "react";
import type { WorkflowVersionDto } from "../types";

interface VersionDiffModalProps {
  isOpen: boolean;
  currentVersion: WorkflowVersionDto;
  historicalVersions?: WorkflowVersionDto[];
  onClose: () => void;
}

export function VersionDiffModal({
  isOpen,
  currentVersion,
  historicalVersions = [],
  onClose,
}: VersionDiffModalProps) {
  const [selectedBaseVersionId, setSelectedBaseVersionId] = useState<string>(
    historicalVersions[0]?.id || "",
  );

  if (!isOpen) return null;

  const baseVersion =
    historicalVersions.find((v) => v.id === selectedBaseVersionId) ||
    historicalVersions[0];

  const currentNodes = currentVersion.nodes;
  const baseNodes = baseVersion?.nodes || [];

  const currentEdges = currentVersion.edges;
  const baseEdges = baseVersion?.edges || [];

  // Compute node diffs
  const addedNodes = currentNodes.filter((cn) => !baseNodes.some((bn) => bn.id === cn.id));
  const removedNodes = baseNodes.filter((bn) => !currentNodes.some((cn) => cn.id === bn.id));
  const modifiedNodes = currentNodes.filter((cn) => {
    const bn = baseNodes.find((b) => b.id === cn.id);
    if (!bn) return false;
    return (
      bn.data.label !== cn.data.label ||
      bn.data.nodeType !== cn.data.nodeType ||
      JSON.stringify(bn.data.config) !== JSON.stringify(cn.data.config)
    );
  });

  // Compute edge diffs
  const addedEdges = currentEdges.filter((ce) => !baseEdges.some((be) => be.id === ce.id));
  const removedEdges = baseEdges.filter((be) => !currentEdges.some((ce) => ce.id === be.id));
  const modifiedEdges = currentEdges.filter((ce) => {
    const be = baseEdges.find((b) => b.id === ce.id);
    if (!be) return false;
    return (
      be.target !== ce.target ||
      be.label !== ce.label ||
      JSON.stringify(be.data) !== JSON.stringify(ce.data)
    );
  });

  return (
    <div
      role="dialog"
      aria-modal="true"
      data-testid="version-diff-modal"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-xs p-4"
    >
      <div className="w-full max-w-4xl rounded-xl bg-white p-6 shadow-2xl space-y-4 max-h-[90vh] flex flex-col animate-scale-in">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-200 pb-3">
          <div>
            <h3 className="text-sm font-bold text-slate-900">
              Workflow Semantic Version Diff
            </h3>
            <p className="text-xs text-slate-500">
              Compare structural and configuration changes between draft and previous releases.
            </p>
          </div>
          <button
            type="button"
            data-testid="close-diff-modal-btn"
            onClick={onClose}
            className="rounded p-1 text-slate-400 hover:text-slate-600"
          >
            ✕
          </button>
        </div>

        {/* Version Compare Selector */}
        <div className="flex items-center justify-between rounded-lg bg-slate-50 p-3 border border-slate-200 text-xs">
          <div className="flex items-center gap-2">
            <span className="font-semibold text-slate-700">Current:</span>
            <span className="rounded bg-blue-100 px-2 py-0.5 font-mono font-bold text-blue-800">
              Version #{currentVersion.versionNo} ({currentVersion.status})
            </span>
          </div>

          <div className="flex items-center gap-2">
            <span className="font-semibold text-slate-700">Compare With:</span>
            {historicalVersions.length > 0 ? (
              <select
                data-testid="select-diff-base-version"
                value={selectedBaseVersionId}
                onChange={(e) => setSelectedBaseVersionId(e.target.value)}
                className="rounded border border-slate-300 bg-white px-2.5 py-1 font-semibold text-xs text-slate-800"
              >
                {historicalVersions.map((v) => (
                  <option key={v.id} value={v.id}>
                    Version #{v.versionNo} ({v.status})
                  </option>
                ))}
              </select>
            ) : (
              <span className="text-slate-400 italic">No historical version available</span>
            )}
          </div>
        </div>

        {/* Summary Stats */}
        <div className="grid grid-cols-4 gap-3 text-xs">
          <div className="rounded-lg border border-slate-200 bg-white p-2.5 shadow-2xs">
            <span className="text-[11px] text-slate-500 block">Nodes Added</span>
            <span
              data-testid="diff-nodes-added-count"
              className="text-base font-bold text-emerald-600"
            >
              +{addedNodes.length}
            </span>
          </div>
          <div className="rounded-lg border border-slate-200 bg-white p-2.5 shadow-2xs">
            <span className="text-[11px] text-slate-500 block">Nodes Removed</span>
            <span
              data-testid="diff-nodes-removed-count"
              className="text-base font-bold text-rose-600"
            >
              -{removedNodes.length}
            </span>
          </div>
          <div className="rounded-lg border border-slate-200 bg-white p-2.5 shadow-2xs">
            <span className="text-[11px] text-slate-500 block">Nodes Modified</span>
            <span
              data-testid="diff-nodes-modified-count"
              className="text-base font-bold text-amber-600"
            >
              ~{modifiedNodes.length}
            </span>
          </div>
          <div className="rounded-lg border border-slate-200 bg-white p-2.5 shadow-2xs">
            <span className="text-[11px] text-slate-500 block">Edge Transitions</span>
            <span className="text-base font-bold text-slate-700">
              +{addedEdges.length} / -{removedEdges.length}
            </span>
          </div>
        </div>

        {/* Detailed Diff List */}
        <div className="flex-1 overflow-y-auto space-y-3 pr-1">
          {/* Added Nodes */}
          {addedNodes.length > 0 && (
            <div className="space-y-1.5" data-testid="diff-section-added-nodes">
              <h5 className="text-xs font-bold text-emerald-800">
                Added Nodes ({addedNodes.length})
              </h5>
              {addedNodes.map((n) => (
                <div
                  key={n.id}
                  className="rounded-lg border border-emerald-200 bg-emerald-50/50 p-2 text-xs flex items-center justify-between"
                >
                  <div className="flex items-center gap-2">
                    <span className="font-bold text-emerald-700">+</span>
                    <span className="font-semibold text-slate-800">{n.data.label}</span>
                    <span className="font-mono text-[10px] text-slate-500">
                      ({n.data.nodeType})
                    </span>
                  </div>
                  <span className="font-mono text-[10px] text-slate-400">{n.id}</span>
                </div>
              ))}
            </div>
          )}

          {/* Removed Nodes */}
          {removedNodes.length > 0 && (
            <div className="space-y-1.5" data-testid="diff-section-removed-nodes">
              <h5 className="text-xs font-bold text-rose-800">
                Removed Nodes ({removedNodes.length})
              </h5>
              {removedNodes.map((n) => (
                <div
                  key={n.id}
                  className="rounded-lg border border-rose-200 bg-rose-50/50 p-2 text-xs flex items-center justify-between"
                >
                  <div className="flex items-center gap-2">
                    <span className="font-bold text-rose-700">-</span>
                    <span className="line-through text-slate-600">{n.data.label}</span>
                    <span className="font-mono text-[10px] text-slate-400">
                      ({n.data.nodeType})
                    </span>
                  </div>
                  <span className="font-mono text-[10px] text-slate-400">{n.id}</span>
                </div>
              ))}
            </div>
          )}

          {/* Modified Nodes */}
          {modifiedNodes.length > 0 && (
            <div className="space-y-1.5" data-testid="diff-section-modified-nodes">
              <h5 className="text-xs font-bold text-amber-800">
                Modified Nodes ({modifiedNodes.length})
              </h5>
              {modifiedNodes.map((cn) => {
                const bn = baseNodes.find((b) => b.id === cn.id)!;
                return (
                  <div
                    key={cn.id}
                    className="rounded-lg border border-amber-200 bg-amber-50/50 p-2.5 text-xs space-y-1"
                  >
                    <div className="flex items-center justify-between">
                      <span className="font-semibold text-slate-800">
                        {cn.data.label}
                      </span>
                      <span className="font-mono text-[10px] text-slate-500">
                        {cn.id}
                      </span>
                    </div>
                    {bn.data.label !== cn.data.label && (
                      <p className="text-[11px] text-slate-600">
                        Label renamed from &quot;{bn.data.label}&quot; → &quot;{cn.data.label}&quot;
                      </p>
                    )}
                    {JSON.stringify(bn.data.config) !== JSON.stringify(cn.data.config) && (
                      <p className="text-[11px] text-slate-600 font-mono">
                        Config payload changed
                      </p>
                    )}
                  </div>
                );
              })}
            </div>
          )}

          {/* Edge Diffs */}
          {(addedEdges.length > 0 || removedEdges.length > 0 || modifiedEdges.length > 0) && (
            <div className="space-y-1.5" data-testid="diff-section-edges">
              <h5 className="text-xs font-bold text-slate-800">
                Edge Transitions Diff
              </h5>
              {addedEdges.map((e) => (
                <div
                  key={e.id}
                  className="rounded border border-emerald-200 bg-emerald-50/40 p-2 text-xs text-emerald-800"
                >
                  + Added transition: {e.source} → {e.target} {e.label ? `("${e.label}")` : ""}
                </div>
              ))}
              {removedEdges.map((e) => (
                <div
                  key={e.id}
                  className="rounded border border-rose-200 bg-rose-50/40 p-2 text-xs text-rose-800"
                >
                  - Removed transition: {e.source} → {e.target}
                </div>
              ))}
            </div>
          )}

          {addedNodes.length === 0 &&
            removedNodes.length === 0 &&
            modifiedNodes.length === 0 &&
            addedEdges.length === 0 &&
            removedEdges.length === 0 && (
              <div className="p-8 text-center text-xs text-slate-400">
                No semantic differences detected between Version #{currentVersion.versionNo} and Version #{baseVersion?.versionNo}.
              </div>
            )}
        </div>
      </div>
    </div>
  );
}
