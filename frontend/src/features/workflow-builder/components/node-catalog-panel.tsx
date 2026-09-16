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
  const [collapsed, setCollapsed] = useState(false);

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

  const categories = [
    "Control",
    "Human",
    "Routing",
    "Integration",
    "Communication",
  ] as const;
  const categoryLabels: Record<(typeof categories)[number], string> = {
    Control: "Điều khiển",
    Human: "Người xử lý",
    Routing: "Định tuyến",
    Integration: "Tích hợp",
    Communication: "Giao tiếp",
  };

  return (
    <aside
      data-testid="node-catalog-panel"
      className={`flex h-full shrink-0 flex-col border-r border-slate-200 bg-white transition-[width] duration-200 ${collapsed ? "w-12" : "w-72"}`}
    >
      {collapsed ? (
        <div className="flex flex-1 justify-center pt-3">
          <button
            type="button"
            data-testid="expand-node-catalog"
            aria-label="Mở danh mục bước"
            aria-expanded={false}
            onClick={() => setCollapsed(false)}
            className="flex h-8 w-8 items-center justify-center rounded-lg text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-800"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="m9 5 7 7-7 7" />
            </svg>
          </button>
        </div>
      ) : (
        <>
          <div className="border-b border-slate-200 p-4">
            <div className="flex items-center justify-between gap-2">
              <h2 className="text-sm font-bold text-slate-900">Danh mục bước</h2>
              <div className="flex items-center gap-1">
                {readOnly && (
                  <span className="rounded bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-600">
                    Chỉ xem
                  </span>
                )}
                <button
                  type="button"
                  data-testid="collapse-node-catalog"
                  aria-label="Thu nhỏ danh mục bước"
                  aria-expanded={true}
                  onClick={() => setCollapsed(true)}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700"
                >
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="m15 5-7 7 7 7" />
                  </svg>
                </button>
              </div>
            </div>
            <div className="mt-3">
              <input
                type="text"
                placeholder="Tìm bước…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs text-slate-800 placeholder-slate-400 focus:border-blue-500 focus:outline-none"
              />
            </div>
          </div>

          <div className="flex-1 space-y-5 overflow-y-auto p-3">
            {categories.map((cat) => {
              const items = filteredItems.filter((i) => i.category === cat);
              if (items.length === 0) return null;

              return (
                <div key={cat} className="space-y-2">
                  <h3 className="px-1 text-[11px] font-bold tracking-wider text-slate-400 uppercase">
                    {categoryLabels[cat]}
                  </h3>
                  <div className="space-y-1.5">
                    {items.map((item) => (
                      <div
                        key={item.type}
                        data-testid={`catalog-node-${item.type.toLowerCase()}`}
                        draggable={!readOnly}
                        aria-label={`Kéo bước ${item.name} vào bảng vẽ`}
                        onDragStart={(event) => {
                          if (readOnly) return;
                          event.dataTransfer.effectAllowed = "move";
                          event.dataTransfer.setData(
                            "application/x-workflow-node",
                            item.type,
                          );
                          // React Flow also recognizes this conventional MIME type.
                          event.dataTransfer.setData(
                            "application/reactflow",
                            item.type,
                          );
                        }}
                        className={`flex items-center justify-between rounded-lg border border-slate-200 p-2.5 transition-colors hover:border-slate-300 hover:bg-slate-50/70 ${
                          readOnly
                            ? "cursor-default"
                            : "cursor-grab active:cursor-grabbing"
                        }`}
                      >
                        <div className="min-w-0 pr-2">
                          <div className="flex items-center gap-1.5">
                            <span className="truncate text-xs font-semibold text-slate-800">
                              {item.name}
                            </span>
                            <span className="py-0.2 rounded bg-slate-100 px-1.5 text-[9px] font-medium text-slate-600">
                              {item.outputPorts.length} cổng
                            </span>
                          </div>
                        </div>

                        <button
                          type="button"
                          data-testid={`add-node-${item.type.toLowerCase()}`}
                          disabled={readOnly}
                          onClick={() => onAddNode(item.type)}
                          className="shrink-0 rounded-md bg-blue-50 p-1.5 text-blue-600 transition-colors hover:bg-blue-100 disabled:cursor-not-allowed disabled:opacity-40"
                          title={readOnly ? "Bảng vẽ chỉ xem" : "Thêm vào bảng vẽ"}
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
        </>
      )}
    </aside>
  );
}
