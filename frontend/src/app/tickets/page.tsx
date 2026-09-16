"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  fetchMyTickets,
  getTicketDisplayName,
  getTicketSummary,
  type TicketView,
} from "@/features/runtime";
import { LoadingState } from "@/shared/components/ui/loading-state";
import { ErrorState } from "@/shared/components/ui/error-state";
import { PageHeader } from "@/shared/components/ui/page-header";
import { StatusBadge } from "@/shared/components/ui/status-badge";

export default function TicketsPage() {
  const [tickets, setTickets] = useState<TicketView[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadTickets = () => {
    setIsLoading(true);
    setError(null);
    fetchMyTickets()
      .then((data) => {
        setTickets(data);
      })
      .catch((err: unknown) => {
        setError(
          err instanceof Error ? err.message : "Không thể tải danh sách ticket",
        );
      })
      .finally(() => {
        setIsLoading(false);
      });
  };

  useEffect(() => {
    let ignore = false;
    fetchMyTickets()
      .then((data) => {
        if (!ignore) {
          setTickets(data);
          setIsLoading(false);
        }
      })
      .catch((err: unknown) => {
        if (!ignore) {
          setError(
            err instanceof Error ? err.message : "Không thể tải danh sách ticket",
          );
          setIsLoading(false);
        }
      });
    return () => {
      ignore = true;
    };
  }, []);

  return (
    <div className="space-y-7" data-testid="tickets-page">
      <PageHeader
        eyebrow="Yêu cầu của tôi"
        title="Ticket của tôi"
        description="Theo dõi các yêu cầu bạn đã gửi và trạng thái xử lý của từng yêu cầu."
        action={<Link
          href="/catalog"
          className="inline-flex items-center gap-2 rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-slate-800"
        >
          + Tạo yêu cầu
        </Link>}
      />

      {isLoading ? (
        <LoadingState title="Đang tải danh sách ticket…" />
      ) : error ? (
        <ErrorState
          title="Không thể tải danh sách ticket"
          message={error}
          onRetry={loadTickets}
        />
      ) : tickets.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 p-12 text-center">
          <h3 className="text-sm font-semibold text-slate-800">
            Chưa có ticket nào
          </h3>
          <p className="mt-1 text-xs text-slate-500">
            Bạn chưa gửi yêu cầu nào.
          </p>
          <div className="mt-4">
            <Link
              href="/catalog"
              className="inline-flex rounded-lg bg-blue-600 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-blue-700"
            >
              Xem danh mục yêu cầu
            </Link>
          </div>
        </div>
      ) : (
        <div className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm">
          <div className="overflow-x-auto">
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50/80 text-[11px] font-bold tracking-wide text-slate-500 uppercase">
              <tr>
                <th className="px-5 py-3">Nội dung yêu cầu</th>
                <th className="px-5 py-3">Trạng thái</th>
                <th className="px-5 py-3">Cập nhật gần nhất</th>
                <th className="px-5 py-3">Thao tác</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {tickets.map((t) => (
                <tr key={t.id} className="transition-colors hover:bg-slate-50/70">
                  <td className="px-5 py-4">
                    <p className="font-semibold text-slate-800">
                      {getTicketDisplayName(t)}
                    </p>
                    {getTicketSummary(t) ? (
                      <p className="mt-1 max-w-md truncate text-xs text-slate-500">
                        {getTicketSummary(t)}
                      </p>
                    ) : null}
                    <p className="mt-1 text-[11px] text-slate-400">
                      Ticket #{t.id.slice(0, 8)} · Tạo ngày{" "}
                      {new Date(t.createdAt).toLocaleDateString("vi-VN")}
                    </p>
                  </td>
                  <td className="px-5 py-4">
                    <StatusBadge value={t.status} />
                  </td>
                  <td className="px-5 py-4 text-xs text-slate-500">
                    {new Date(t.updatedAt).toLocaleDateString("vi-VN")}
                  </td>
                  <td className="px-5 py-4">
                    <Link
                      href={`/tickets/${t.id}`}
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
