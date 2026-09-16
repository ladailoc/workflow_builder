"use client";

import { useEffect, useState } from "react";
import { apiGet } from "@/shared/api/client";
import { ErrorState } from "@/shared/components/ui/error-state";
import { LoadingState } from "@/shared/components/ui/loading-state";
import { PageHeader } from "@/shared/components/ui/page-header";
import { StatusBadge } from "@/shared/components/ui/status-badge";

interface NotificationItem {
  id: string;
  eventId: string;
  taskId?: string | null;
  channel: string;
  status: string;
  dedupKey: string;
  template?: { subject?: string; body?: string } | null;
  payload: Record<string, unknown>;
  createdAt: string;
  sentAt?: string | null;
}

export default function NotificationsPage() {
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    setError(null);
    apiGet<NotificationItem[]>("/api/v1/notifications")
      .then(setItems)
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Không thể tải thông báo"))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    void Promise.resolve().then(() => load());
  }, []);

  return (
    <div className="space-y-7" data-testid="notifications-page">
      <PageHeader eyebrow="Trung tâm thông báo" title="Thông báo của tôi" description="Theo dõi các thông báo đã được lưu và trạng thái gửi của từng kênh." />
      {loading ? <LoadingState title="Đang tải thông báo…" /> : error ? (
        <ErrorState title="Không thể tải thông báo" message={error} onRetry={load} />
      ) : items.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 p-12 text-center text-xs text-slate-500">Chưa có thông báo nào.</div>
      ) : (
        <div className="space-y-3">
          {items.map((item) => (
            <article key={item.id} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h2 className="text-sm font-semibold text-slate-900">{item.template?.subject ?? "Thông báo quy trình"}</h2>
                  <p className="mt-1 text-xs text-slate-500">{item.template?.body ?? `Sự kiện #${item.eventId.slice(0, 8)}`}</p>
                </div>
                <div className="flex items-center gap-2 text-[11px]">
                  <span className="rounded bg-slate-100 px-2 py-1 font-semibold text-slate-600">{item.channel}</span>
                  <StatusBadge value={item.status} />
                </div>
              </div>
              <p className="mt-3 text-[11px] text-slate-400">{new Date(item.createdAt).toLocaleString("vi-VN")}</p>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
