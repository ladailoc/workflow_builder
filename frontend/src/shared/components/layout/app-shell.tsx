"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { useAuthSession } from "@/features/auth";
import { Header } from "./header";
import { Sidebar } from "./sidebar";

interface AppShellProps {
  children: ReactNode;
}

export function AppShell({ children }: Readonly<AppShellProps>) {
  return (
    <div data-testid="app-shell" className="flex min-h-screen w-full bg-[var(--background)]">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        <Header />
        <MobileNav />
        <main className="flex-1 px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
          <div className="mx-auto w-full max-w-[1440px]">{children}</div>
        </main>
      </div>
    </div>
  );
}

function MobileNav() {
  const pathname = usePathname();
  const { canAccessWorkflowManagement, canAccessOperations } = useAuthSession();
  const links = [
    ["Tổng quan", "/"],
    ["Danh mục yêu cầu", "/catalog"],
    ["Ticket của tôi", "/tickets"],
    ["Công việc", "/tasks"],
    ["Thông báo", "/notifications"],
    ...(canAccessWorkflowManagement ? [["Quản trị", "/workflows"]] : []),
    ...(canAccessOperations ? [["Vận hành", "/operations"]] : []),
  ];

  return (
    <nav
      aria-label="Điều hướng trên thiết bị di động"
      className="flex gap-1 overflow-x-auto border-b border-slate-200/80 bg-white px-3 py-2 lg:hidden"
    >
      {links.map(([label, href]) => {
        const active = href === "/" ? pathname === "/" : pathname.startsWith(href);
        return (
          <Link
            key={href}
            href={href}
            className={`shrink-0 rounded-lg px-3 py-2 text-xs font-semibold ${
              active ? "bg-blue-50 text-blue-700" : "text-slate-500"
            }`}
          >
            {label}
          </Link>
        );
      })}
    </nav>
  );
}
