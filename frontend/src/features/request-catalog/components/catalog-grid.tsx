"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import type { CatalogItem } from "../types";

interface CatalogGridProps {
  items: CatalogItem[];
  isLoading?: boolean;
}

function categoryLabel(category?: string): string {
  const labels: Record<string, string> = {
    GENERAL: "Chung",
    General: "Chung",
    IT: "Công nghệ thông tin",
    "IT ACCESS": "Quyền truy cập công nghệ thông tin",
    "IT EQUIPMENT": "Thiết bị công nghệ thông tin",
    HR: "Nhân sự",
    FINANCE: "Tài chính",
    PROCUREMENT: "Mua sắm",
    SECURITY: "An ninh",
  };
  const normalized = category?.toUpperCase();
  return labels[normalized ?? ""] ?? category ?? "Chung";
}

export function CatalogGrid({ items, isLoading = false }: CatalogGridProps) {
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedCategory, setSelectedCategory] = useState<string>("ALL");

  const categories = useMemo(() => {
    const set = new Set<string>();
    items.forEach((item) => {
      if (item.category) {
        set.add(item.category);
      }
    });
    return ["ALL", ...Array.from(set).sort()];
  }, [items]);

  const filteredItems = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    return items.filter((item) => {
      const matchesCategory =
        selectedCategory === "ALL" || item.category === selectedCategory;
      const matchesSearch =
        !query ||
        item.name.toLowerCase().includes(query) ||
        item.description.toLowerCase().includes(query);
      return matchesCategory && matchesSearch;
    });
  }, [items, searchQuery, selectedCategory]);

  const groupedByCategory = useMemo(() => {
    const map = new Map<string, CatalogItem[]>();
    filteredItems.forEach((item) => {
      const cat = item.category || "General";
      if (!map.has(cat)) {
        map.set(cat, []);
      }
      map.get(cat)!.push(item);
    });
    return map;
  }, [filteredItems]);

  if (isLoading) {
    return (
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {[1, 2, 3, 4, 5, 6].map((i) => (
          <div
            key={i}
            className="h-44 rounded-xl border border-slate-200 bg-white p-5 animate-pulse"
          >
            <div className="h-4 w-20 bg-slate-200 rounded mb-3" />
            <div className="h-5 w-48 bg-slate-200 rounded mb-2" />
            <div className="h-12 w-full bg-slate-100 rounded" />
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="space-y-7">
      {/* Search and Category Filter Controls */}
      <div className="flex flex-col gap-4 rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm sm:flex-row sm:items-center sm:justify-between">
        <div className="relative max-w-md flex-1">
          <input
            type="text"
            data-testid="catalog-search-input"
            aria-label="Tìm trong danh mục yêu cầu"
            placeholder="Tìm dịch vụ"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2 pl-9 text-sm text-slate-800 placeholder-slate-400 shadow-2xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
          <svg
            className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
            />
          </svg>
        </div>

        {/* Category Pills */}
        <div className="flex flex-wrap gap-1.5" data-testid="catalog-categories">
          {categories.map((cat) => (
            <button
              key={cat}
              type="button"
              data-testid={`category-filter-${cat.toLowerCase()}`}
              onClick={() => setSelectedCategory(cat)}
              className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                selectedCategory === cat
                  ? "bg-blue-600 text-white shadow-2xs"
                  : "bg-slate-100 text-slate-600 hover:bg-slate-200 hover:text-slate-900"
              }`}
            >
              {cat === "ALL" ? "Tất cả danh mục" : categoryLabel(cat)}
            </button>
          ))}
        </div>
      </div>

      {filteredItems.length === 0 ? (
        <div
          data-testid="catalog-empty-state"
          className="rounded-xl border border-dashed border-slate-300 p-12 text-center"
        >
          <svg
            className="mx-auto h-12 w-12 text-slate-400"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={1.5}
              d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"
            />
          </svg>
          <h3 className="mt-2 text-sm font-semibold text-slate-800">
            Không tìm thấy loại yêu cầu
          </h3>
        </div>
      ) : (
        <div className="space-y-8">
          {Array.from(groupedByCategory.entries()).map(([category, itemsInCat]) => (
            <div key={category} className="space-y-4">
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-bold tracking-wide text-slate-700 uppercase">
                  {categoryLabel(category)}
                </h3>
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600">
                  {itemsInCat.length}
                </span>
              </div>

              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {itemsInCat.map((item) => (
                  <div
                    key={item.id}
                    data-testid={`catalog-card-${item.key}`}
                    className="group flex min-h-48 flex-col justify-between rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm transition hover:-translate-y-0.5 hover:border-blue-200 hover:shadow-md"
                  >
                    <div>
                      <div className="flex items-center justify-between gap-2">
                        <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-blue-50 text-lg text-blue-700">
                          {item.icon || "✦"}
                        </span>
                        <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[10px] font-semibold text-slate-500">
                          {categoryLabel(item.category)}
                        </span>
                      </div>
                      {/* Business title: prominent and user-facing */}
                      <h4
                        data-testid="request-type-title"
                        className="mt-4 text-base font-semibold leading-snug text-slate-950"
                      >
                        {item.name}
                      </h4>
                      <p className="mt-1.5 text-xs text-slate-500 line-clamp-3 leading-relaxed">
                        {item.description || "Gửi yêu cầu mới cho dịch vụ này."}
                      </p>
                    </div>

                    <div className="mt-5 border-t border-slate-100 pt-3">
                      <Link
                        href={`/catalog/${encodeURIComponent(item.key)}`}
                        data-testid={`start-request-${item.key}`}
                        className="inline-flex w-full items-center justify-center gap-2 rounded-xl bg-slate-950 px-3 py-2.5 text-xs font-semibold text-white shadow-sm transition-colors hover:bg-blue-700"
                      >
                        <span>Tạo yêu cầu</span>
                        <svg
                          className="h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M14 5l7 7m0 0l-7 7m7-7H3"
                          />
                        </svg>
                      </Link>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
