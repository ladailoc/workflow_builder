"use client";

import { useAuthSession } from "@/features/auth";
import { Breadcrumbs } from "./breadcrumbs";

export function Header() {
  const { actor } = useAuthSession();

  return (
    <header
      data-testid="app-header"
      className="sticky top-0 z-20 flex min-h-16 shrink-0 items-center justify-between border-b border-slate-200/80 bg-white/95 px-4 backdrop-blur sm:px-6"
    >
      <div className="flex items-center gap-4">
        <Breadcrumbs />
      </div>

      <div className="flex items-center gap-3">
        <div className="hidden items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-xs font-medium text-emerald-800 sm:flex">
          <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
          <span>Hệ thống đang hoạt động</span>
        </div>

        {actor && (
          <div className="flex items-center gap-2 rounded-full border border-slate-200 bg-white px-2 py-1.5 text-xs text-slate-700 shadow-xs">
            <div className="flex h-6 w-6 items-center justify-center rounded-full bg-slate-900 text-[10px] font-bold text-white">
              {actor.principalName.charAt(0)}
            </div>
            <span className="font-medium">{actor.principalName}</span>
          </div>
        )}
      </div>
    </header>
  );
}
