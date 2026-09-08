import type { Metadata } from "next";
import type { ReactNode } from "react";

import "./globals.css";
import { Providers } from "./providers";
import { AppShell } from "@/shared/components/layout/app-shell";

export const metadata: Metadata = {
  title: "Workflow Platform",
  description: "Definition-driven enterprise workflow platform",
};

export default function RootLayout({
  children,
}: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en">
      <body className="antialiased font-sans text-slate-900 bg-slate-50">
        <Providers>
          <AppShell>{children}</AppShell>
        </Providers>
      </body>
    </html>
  );
}
