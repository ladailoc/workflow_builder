"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { apiGet } from "@/shared/api/client";
import { LoadingState } from "@/shared/components/ui/loading-state";
import { ErrorState } from "@/shared/components/ui/error-state";
import { PageHeader } from "@/shared/components/ui/page-header";
import { formatOutcome, StatusBadge } from "@/shared/components/ui/status-badge";

interface EventSummary {
  id: string;
  ticketId: string;
  status: string;
  outcome?: string | null;
  createdAt: string;
}

export default function EventsPage() {
  const [events, setEvents] = useState<EventSummary[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadEvents = () => {
    setIsLoading(true);
    setError(null);
    apiGet<EventSummary[]>("/api/v1/events")
      .then((data) => {
        setEvents(data);
      })
      .catch((err: unknown) => {
        setError(
          err instanceof Error ? err.message : "Không thể tải lịch sử xử lý",
        );
      })
      .finally(() => {
        setIsLoading(false);
      });
  };

  useEffect(() => {
    let ignore = false;
    apiGet<EventSummary[]>("/api/v1/events")
      .then((data) => {
        if (!ignore) {
          setEvents(data);
          setIsLoading(false);
        }
      })
      .catch((err: unknown) => {
        if (!ignore) {
          setError(
            err instanceof Error ? err.message : "Không thể tải lịch sử xử lý",
          );
          setIsLoading(false);
        }
      });
    return () => {
      ignore = true;
    };
  }, []);

  return (
    <div className="space-y-7" data-testid="events-page">
      <PageHeader eyebrow="Giám sát nâng cao" title="Lịch sử xử lý" description="Theo dõi chi tiết quá trình xử lý dành cho người vận hành và quản trị viên." />

      {isLoading ? (
        <LoadingState title="Đang tải lịch sử xử lý…" />
      ) : error ? (
        <ErrorState
          title="Không thể tải lịch sử xử lý"
          message={error}
          onRetry={loadEvents}
        />
      ) : events.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 p-12 text-center text-xs text-slate-500">
          Hiện chưa có hoạt động nào được ghi nhận.
        </div>
      ) : (
        <div className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm">
          <div className="overflow-x-auto"><table className="w-full min-w-[720px] text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-bold tracking-wide text-slate-500 uppercase">
              <tr>
                <th className="px-5 py-3">Mã sự kiện</th>
                <th className="px-5 py-3">Trạng thái</th>
                <th className="px-5 py-3">Kết quả</th>
                <th className="px-5 py-3">Ticket</th>
                <th className="px-5 py-3">Ngày tạo</th>
                <th className="px-5 py-3">Thao tác</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {events.map((e) => (
                <tr key={e.id} className="transition-colors hover:bg-slate-50/70">
                  <td className="px-5 py-4 font-mono text-xs font-semibold text-slate-800">
                    #{e.id.slice(0, 8)}
                  </td>
                  <td className="px-5 py-4">
                    <StatusBadge value={e.status} />
                  </td>
                  <td className="px-5 py-4 text-sm text-slate-600">{e.outcome ? formatOutcome(e.outcome) : "—"}</td>
                  <td className="px-5 py-4 font-mono text-xs text-slate-500">
                    #{e.ticketId.slice(0, 8)}
                  </td>
                  <td className="px-5 py-4 text-xs text-slate-500">
                    {new Date(e.createdAt).toLocaleDateString("vi-VN")}
                  </td>
                  <td className="px-5 py-4">
                    <Link
                      href={`/events/${e.id}`}
                      className="font-semibold text-blue-700 hover:text-blue-900"
                    >
                      Xem chi tiết →
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        </div>
      )}
    </div>
  );
}
