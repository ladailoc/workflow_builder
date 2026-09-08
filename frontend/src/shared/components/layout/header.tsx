"use client";

import { useAuthSession } from "@/features/auth";
import { Breadcrumbs } from "./breadcrumbs";

export function Header() {
  const { actor } = useAuthSession();

  return (
    <header
      data-testid="app-header"
      className="flex h-16 shrink-0 items-center justify-between border-b border-slate-200 bg-white px-6"
    >
      <div className="flex items-center gap-4">
        <Breadcrumbs />
      </div>

      <div className="flex items-center gap-3">
        <div className="hidden sm:flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-xs font-medium text-emerald-800">
          <span className="h-2 w-2 rounded-full bg-emerald-500 animate-pulse" />
          <span>Platform Active</span>
        </div>

        {actor && (
          <div className="flex items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-1.5 text-xs text-slate-700">
            <div className="flex h-5 w-5 items-center justify-center rounded-full bg-blue-600 text-[10px] font-bold text-white">
              {actor.principalName.charAt(0)}
            </div>
            <span className="font-medium">{actor.principalName}</span>
          </div>
        )}
      </div>
    </header>
  );
}
