"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const ROUTE_LABELS: Record<string, string> = {
  catalog: "Request Catalog",
  tickets: "My Tickets",
  tasks: "My Tasks",
  events: "Events & History",
  workflows: "Workflow Builder",
  organization: "Organization & Admin",
  operations: "Operations",
};

export function Breadcrumbs() {
  const pathname = usePathname();
  const segments = pathname.split("/").filter(Boolean);

  if (segments.length === 0) {
    return (
      <nav aria-label="Breadcrumb" className="flex items-center text-xs font-medium text-slate-500">
        <span className="text-slate-900 font-semibold">Home</span>
      </nav>
    );
  }

  return (
    <nav
      aria-label="Breadcrumb"
      className="flex items-center space-x-1.5 text-xs font-medium text-slate-500"
    >
      <Link
        href="/catalog"
        className="text-slate-500 hover:text-slate-900 transition-colors"
      >
        Home
      </Link>
      {segments.map((segment, index) => {
        const accumulatedPath = "/" + segments.slice(0, index + 1).join("/");
        const isLast = index === segments.length - 1;
        const label =
          ROUTE_LABELS[segment] ??
          (segment.length > 20
            ? `${segment.slice(0, 8)}...${segment.slice(-4)}`
            : segment.charAt(0).toUpperCase() + segment.slice(1));

        return (
          <span key={accumulatedPath} className="flex items-center space-x-1.5">
            <svg
              className="h-3.5 w-3.5 text-slate-400"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M9 5l7 7-7 7"
              />
            </svg>
            {isLast ? (
              <span aria-current="page" className="font-semibold text-slate-900">
                {label}
              </span>
            ) : (
              <Link
                href={accumulatedPath}
                className="text-slate-500 hover:text-slate-900 transition-colors"
              >
                {label}
              </Link>
            )}
          </span>
        );
      })}
    </nav>
  );
}
