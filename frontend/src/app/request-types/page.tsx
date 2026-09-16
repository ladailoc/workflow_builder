"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AuthRouteGuard } from "@/features/auth";
import {
  AdminPageHeader,
  EmptyPanel,
  LifecycleBadge,
  LoadingPanel,
  PrimaryLink,
  fetchAdminRequestTypes,
  type RequestTypeAdminView,
} from "@/features/workflow-management";
import { ErrorState } from "@/shared/components/ui/error-state";

function categoryLabel(category?: string): string {
  const labels: Record<string, string> = {
    GENERAL: "Chung",
    IT: "Công nghệ thông tin",
    HR: "Nhân sự",
    FINANCE: "Tài chính",
    PROCUREMENT: "Mua sắm",
    SECURITY: "An ninh",
  };
  const normalized = category?.toUpperCase();
  return labels[normalized ?? ""] ?? category ?? "Chung";
}

export default function RequestTypesPage() {
  const [items, setItems] = useState<RequestTypeAdminView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const load = () => {
    setLoading(true);
    setError(null);
    fetchAdminRequestTypes()
      .then((page) => setItems(page.items))
      .catch((reason: unknown) =>
        setError(
          reason instanceof Error
            ? reason.message
            : "Không thể tải loại yêu cầu",
        ),
      )
      .finally(() => setLoading(false));
  };
  useEffect(() => {
    let ignore = false;
    fetchAdminRequestTypes()
      .then((page) => {
        if (!ignore) setItems(page.items);
      })
      .catch((reason: unknown) => {
        if (!ignore)
          setError(
            reason instanceof Error
              ? reason.message
              : "Không thể tải loại yêu cầu",
          );
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });
    return () => {
      ignore = true;
    };
  }, []);

  return (
    <AuthRouteGuard roles={["WORKFLOW_OWNER", "ADMIN"]}>
      <div className="space-y-6" data-testid="request-type-management-page">
        <AdminPageHeader
          title="Loại yêu cầu"
          description="Quản lý các mục trong danh mục yêu cầu và liên kết WorkflowDefinition tương ứng."
          action={
            <PrimaryLink href="/request-types/new">
              + Tạo loại yêu cầu
            </PrimaryLink>
          }
        />
        {error ? (
          <ErrorState
            title="Không thể tải loại yêu cầu"
            message={error}
            onRetry={load}
          />
        ) : loading ? (
          <LoadingPanel label="Đang tải loại yêu cầu…" />
        ) : items.length === 0 ? (
          <EmptyPanel
            title="Chưa có loại yêu cầu"
            detail="Hãy tạo một RequestType dành cho người dùng và liên kết với WorkflowDefinition."
          />
        ) : (
          <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 text-left text-sm">
                <thead className="bg-slate-50 text-xs text-slate-500 uppercase">
                  <tr>
                    <th className="px-5 py-3">Tên</th>
                    <th className="px-5 py-3">Khóa</th>
                    <th className="px-5 py-3">Quy trình</th>
                    <th className="px-5 py-3">Đã phát hành</th>
                    <th className="px-5 py-3">Hiển thị</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {items.map((item) => (
                    <tr
                      key={item.id}
                      data-testid={`request-type-row-${item.id}`}
                    >
                      <td className="px-5 py-4">
                        <Link
                          href={`/request-types/${item.id}`}
                          className="font-semibold text-blue-700"
                        >
                          {item.name}
                        </Link>
                        <p className="text-xs text-slate-500">
                          {categoryLabel(item.category)}
                        </p>
                      </td>
                      <td className="px-5 py-4 font-mono text-xs">
                        {item.key}
                      </td>
                      <td className="px-5 py-4">
                        <span className="font-medium">
                          {item.workflowDefinitionName}
                        </span>
                        <span className="ml-2">
                          <LifecycleBadge value={item.workflowLifecycle} />
                        </span>
                      </td>
                      <td className="px-5 py-4">
                        {item.currentPublishedVersionNo ? (
                          `V${item.currentPublishedVersionNo}`
                        ) : (
                          <span className="text-amber-700">
                            Chưa có schema đã phát hành
                          </span>
                        )}
                      </td>
                      <td className="px-5 py-4">
                        <span
                          className={`rounded-full px-2 py-1 text-xs font-semibold ${item.active ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-600"}`}
                        >
                          {item.active ? "Có" : "Không"}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </AuthRouteGuard>
  );
}
