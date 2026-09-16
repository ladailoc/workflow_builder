"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";

import { PRESET_ACTORS, useAuthSession } from "@/features/auth";

interface NavItem {
  readonly label: string;
  readonly href: string;
  readonly icon: (props: { className?: string }) => React.JSX.Element;
}

const PRIMARY_NAV_ITEMS: readonly NavItem[] = [
  {
    label: "Request Catalog",
    href: "/catalog",
    icon: (props) => (
      <svg
        className={props.className}
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M4 6h16M4 10h16M4 14h16M4 18h16"
        />
      </svg>
    ),
  },
  {
    label: "My Tickets",
    href: "/tickets",
    icon: (props) => (
      <svg
        className={props.className}
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
        />
      </svg>
    ),
  },
  {
    label: "My Tasks",
    href: "/tasks",
    icon: (props) => (
      <svg
        className={props.className}
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4"
        />
      </svg>
    ),
  },
  {
    label: "Notifications",
    href: "/notifications",
    icon: (props) => (
      <svg className={props.className} fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 17h5l-1.4-1.4A2 2 0 0118 14.2V11a6 6 0 10-12 0v3.2a2 2 0 01-.6 1.4L4 17h5m6 0a3 3 0 01-6 0m6 0H9" />
      </svg>
    ),
  },
  {
    label: "Events & History",
    href: "/events",
    icon: (props) => (
      <svg
        className={props.className}
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
        />
      </svg>
    ),
  },
];

export function Sidebar() {
  const pathname = usePathname();
  const {
    actor,
    switchActor,
    canAccessWorkflowManagement,
    canAccessOrganization,
    canAccessOperations,
    hasRole,
  } = useAuthSession();

  const [actorDropdownOpen, setActorDropdownOpen] = useState(false);

  return (
    <aside
      data-testid="app-sidebar"
      className="sticky top-0 hidden h-screen w-64 shrink-0 self-start flex-col border-r border-slate-200/80 bg-white lg:flex"
    >
      {/* Brand / Logo */}
      <div className="flex h-[72px] shrink-0 items-center justify-between border-b border-slate-200/80 px-5">
        <Link href="/" className="flex items-center gap-2.5">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-slate-950 font-bold text-white shadow-sm">
            W
          </div>
          <div>
            <span className="block text-sm leading-none font-semibold tracking-tight text-slate-900">
              Flowdesk
            </span>
            <span className="text-[10px] font-semibold tracking-[0.18em] text-blue-600 uppercase">
              WORKFLOW PLATFORM
            </span>
          </div>
        </Link>
      </div>

      {/* Nav links */}
      <div className="flex-1 space-y-7 overflow-y-auto px-3 py-5">
        {/* Primary Runtime Navigation */}
        <div>
          <p className="px-3 text-[10px] font-bold tracking-[0.18em] text-slate-400 uppercase">
            Workspace
          </p>
          <nav className="mt-2 space-y-1">
            {PRIMARY_NAV_ITEMS.map((item) => {
              const active =
                pathname === item.href || pathname.startsWith(`${item.href}/`);
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-colors ${
                    active
                      ? "bg-blue-50 text-blue-700"
                      : "text-slate-600 hover:bg-slate-100 hover:text-slate-900"
                  }`}
                >
                  <item.icon
                    className={`h-4 w-4 shrink-0 ${
                      active ? "text-blue-600" : "text-slate-400"
                    }`}
                  />
                  <span>{item.label}</span>
                </Link>
              );
            })}
          </nav>
        </div>

        {/* Privileged Administration Section */}
        {(canAccessWorkflowManagement ||
          canAccessOrganization ||
          canAccessOperations) && (
          <div data-testid="privileged-nav-section">
            <p className="px-3 text-[10px] font-bold tracking-[0.18em] text-slate-400 uppercase">
              Administration
            </p>
            <nav className="mt-2 space-y-1">
              {canAccessWorkflowManagement && (
                <Link
                  data-testid="nav-workflows"
                  href="/workflows"
                  className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-colors ${
                    pathname.startsWith("/workflows")
                      ? "bg-blue-50 text-blue-700"
                      : "text-slate-600 hover:bg-slate-100 hover:text-slate-900"
                  }`}
                >
                  <svg
                    className={`h-4 w-4 shrink-0 ${
                      pathname.startsWith("/workflows")
                        ? "text-blue-600"
                        : "text-slate-400"
                    }`}
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M13 10V3L4 14h7v7l9-11h-7z"
                    />
                  </svg>
                  <span>Workflows</span>
                </Link>
              )}

              {hasRole(["WORKFLOW_OWNER", "ADMIN"]) && (
                <>
                  <Link
                    href="/forms"
                    className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium ${pathname.startsWith("/forms") ? "bg-blue-50 text-blue-700" : "text-slate-600 hover:bg-slate-100"}`}
                  >
                    <span>Forms</span>
                  </Link>
                  <Link
                    href="/ticket-categories"
                    className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium ${pathname.startsWith("/ticket-categories") ? "bg-blue-50 text-blue-700" : "text-slate-600 hover:bg-slate-100"}`}
                  >
                    <span>Business Intents</span>
                  </Link>
                  <Link
                    data-testid="nav-request-types"
                    href="/request-types"
                    className={`flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                      pathname.startsWith("/request-types")
                        ? "bg-blue-50 text-blue-700"
                        : "text-slate-600 hover:bg-slate-100 hover:text-slate-900"
                    }`}
                  >
                    <svg
                      className="h-4 w-4 shrink-0 text-slate-400"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M4 6h16M4 12h16M4 18h10"
                      />
                    </svg>
                    <span>Request Types</span>
                  </Link>
                </>
              )}

              {canAccessOrganization && (
                <Link
                  data-testid="nav-organization"
                  href="/organization"
                  className={`flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                    pathname.startsWith("/organization")
                      ? "bg-blue-50 text-blue-700"
                      : "text-slate-600 hover:bg-slate-100 hover:text-slate-900"
                  }`}
                >
                  <svg
                    className={`h-4 w-4 shrink-0 ${
                      pathname.startsWith("/organization")
                        ? "text-blue-600"
                        : "text-slate-400"
                    }`}
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4"
                    />
                  </svg>
                  <span>Organization</span>
                </Link>
              )}

              {canAccessOperations && (
                <Link
                  data-testid="nav-operations"
                  href="/operations"
                  className={`flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                    pathname.startsWith("/operations")
                      ? "bg-blue-50 text-blue-700"
                      : "text-slate-600 hover:bg-slate-100 hover:text-slate-900"
                  }`}
                >
                  <svg
                    className={`h-4 w-4 shrink-0 ${
                      pathname.startsWith("/operations")
                        ? "text-blue-600"
                        : "text-slate-400"
                    }`}
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                    />
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                    />
                  </svg>
                  <span>Operations</span>
                </Link>
              )}
            </nav>
          </div>
        )}
      </div>

      {/* User Session & Actor Switcher Footer */}
      <div className="border-t border-slate-200 bg-slate-50/70 p-3">
        <div className="relative">
          <button
            type="button"
            data-testid="actor-switcher-button"
            onClick={() => setActorDropdownOpen(!actorDropdownOpen)}
            className="flex w-full items-center justify-between rounded-lg border border-slate-200 bg-white p-2.5 text-left text-xs shadow-2xs transition-colors hover:bg-slate-50"
          >
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1.5">
                <span className="truncate font-semibold text-slate-800">
                  {actor ? actor.principalName : "Not signed in"}
                </span>
                {actor && (
                  <span className="py-0.2 rounded bg-blue-100 px-1.5 text-[10px] font-semibold text-blue-700">
                    {actor.roles[0]}
                  </span>
                )}
              </div>
              <p className="mt-0.5 truncate text-[11px] text-slate-500">
                {actor?.email ?? "No active actor"}
              </p>
            </div>
            <svg
              className="ml-1 h-4 w-4 shrink-0 text-slate-400"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 9l-7 7-7-7"
              />
            </svg>
          </button>

          {actorDropdownOpen && (
            <div
              data-testid="actor-switcher-menu"
              className="absolute bottom-full left-0 z-50 mb-2 w-full rounded-xl border border-slate-200 bg-white p-1.5 shadow-lg"
            >
              <div className="px-2 py-1 text-[10px] font-bold tracking-wider text-slate-400 uppercase">
                Simulate Actor Role
              </div>
              {PRESET_ACTORS.map((preset) => {
                const isSelected = actor?.actorId === preset.actorId;
                return (
                  <button
                    key={preset.actorId}
                    type="button"
                    data-testid={`select-actor-${preset.roles[0].toLowerCase()}`}
                    onClick={() => {
                      switchActor(preset);
                      setActorDropdownOpen(false);
                    }}
                    className={`flex w-full items-center justify-between rounded-lg px-2.5 py-1.5 text-left text-xs transition-colors ${
                      isSelected
                        ? "bg-blue-50 font-semibold text-blue-800"
                        : "text-slate-700 hover:bg-slate-100"
                    }`}
                  >
                    <div>
                      <div className="font-medium">{preset.principalName}</div>
                      <div className="font-mono text-[10px] text-slate-400">
                        {preset.roles.join(", ")}
                      </div>
                    </div>
                    {isSelected && (
                      <span className="h-1.5 w-1.5 rounded-full bg-blue-600" />
                    )}
                  </button>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </aside>
  );
}
