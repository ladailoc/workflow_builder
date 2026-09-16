"use client";

import { AuthRouteGuard } from "@/features/auth";
import { OperatorConsole } from "@/features/operations";
import { PageHeader } from "@/shared/components/ui/page-header";

export default function OperationsPage() {
  return (
    <AuthRouteGuard roles={["OPERATOR", "ADMIN"]}>
      <div className="space-y-7" data-testid="operations-page">
        <PageHeader eyebrow="Không gian vận hành" title="Vận hành & tình trạng hệ thống" description="Khôi phục các job, bước xử lý, tích hợp và sự kiện bị lỗi bằng thao tác có ghi nhận lịch sử." />
        <OperatorConsole />
      </div>
    </AuthRouteGuard>
  );
}
