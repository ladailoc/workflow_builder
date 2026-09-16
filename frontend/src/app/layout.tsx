import type { Metadata } from "next";
import type { ReactNode } from "react";

import "./globals.css";
import { Providers } from "./providers";
import { AppShell } from "@/shared/components/layout/app-shell";

export const metadata: Metadata = {
  title: "Flowdesk · Nền tảng quy trình",
  description: "Không gian rõ ràng để tạo yêu cầu, phê duyệt và theo dõi quy trình.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="vi">
      <body className="text-slate-900">
        <Providers>
          <AppShell>{children}</AppShell>
        </Providers>
      </body>
    </html>
  );
}
