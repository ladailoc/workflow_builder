"use client";

import type { ReactNode } from "react";
import { Header } from "./header";
import { Sidebar } from "./sidebar";

interface AppShellProps {
  children: ReactNode;
}

export function AppShell({ children }: Readonly<AppShellProps>) {
  return (
    <div data-testid="app-shell" className="flex h-screen w-screen overflow-hidden bg-slate-50">
      <Sidebar />
      <div className="flex flex-1 flex-col overflow-hidden">
        <Header />
        <main className="flex-1 overflow-y-auto p-6 md:p-8">
          <div className="mx-auto max-w-7xl">{children}</div>
        </main>
      </div>
    </div>
  );
}
