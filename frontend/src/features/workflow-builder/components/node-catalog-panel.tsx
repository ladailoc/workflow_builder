"use client";

import { useMemo, useState } from "react";
import { NODE_CATALOG } from "../manifest";
import type { BuilderNodeType } from "../types";

interface NodeCatalogPanelProps {
  onAddNode: (type: BuilderNodeType) => void;
  readOnly?: boolean;
}

export function NodeCatalogPanel({
  onAddNode,
  readOnly = false,
}: NodeCatalogPanelProps) {
  const [search, setSearch] = useState("");

  const filteredItems = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return NODE_CATALOG;
    return NODE_CATALOG.filter(
      (item) =>
        item.name.toLowerCase().includes(q) ||
        item.description.toLowerCase().includes(q) ||
        item.category.toLowerCase().includes(q),
    );
  }, [search]);

  const categories = ["Control", "Human", "Routing", "Integration", "Communication"] as const;

  return (
    <aside
      data-testid="node-catalog-panel"
      className="flex h-full w-72 flex-col border-r border-slate-200 bg-white"
    >
      <div className="border-b border-slate-200 p-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-bold text-slate-900">Node Catalog</h2>
          {readOnly && (
            <span className="rounded bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-600">
              Read-Only
            </span>
          )}
        </div>
        <p className="mt-1 text-xs text-slate-500">
          Strict NodeType manifest palette.
        </p>
        <div className="mt-3">
          <input
            type="text"
            placeholder="Search nodes..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs text-slate-800 placeholder-slate-400 focus:border-blue-500 focus:outline-none"
          />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-3 space-y-5">
        {categories.map((cat) => {
          const items = filteredItems.filter((i) => i.category === cat);
          if (items.length === 0) return null;

          return (
            <div key={cat} className="space-y-2">
              <h3 className="px-1 text-[11px] font-bold text-slate-400 uppercase tracking-wider">
                {cat}
              </h3>
              <div className="space-y-1.5">
                {items.map((item) => (
                  <div
                    key={item.type}
                    data-testid={`catalog-node-${item.type.toLowerCase()}`}
                    className="flex items-center justify-between rounded-lg border border-slate-200 p-2.5 transition-colors hover:border-slate-300 hover:bg-slate-50/70"
                  >
                    <div className="min-w-0 pr-2">
                      <div className="flex items-center gap-1.5">
                        <span className="text-xs font-semibold text-slate-800 truncate">
                          {item.name}
                        </span>
                        <span className="rounded bg-slate-100 px-1.5 py-0.2 text-[9px] font-medium text-slate-600">
                          {item.outputPorts.length} port
                          {item.outputPorts.length === 1 ? "" : "s"}
                        </span>
                      </div>
                      <p className="mt-0.5 text-[11px] text-slate-400 line-clamp-2 leading-tight">
                        {item.description}
                      </p>
                    </div>

                    <button
                      type="button"
                      data-testid={`add-node-${item.type.toLowerCase()}`}
                      disabled={readOnly}
                      onClick={() => onAddNode(item.type)}
                      className="shrink-0 rounded-md bg-blue-50 p-1.5 text-blue-600 hover:bg-blue-100 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                      title={readOnly ? "Canvas is read-only" : "Add to canvas"}
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
                          d="M12 4v16m8-8H4"
                        />
                      </svg>
                    </button>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </aside>
  );
}
