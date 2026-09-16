"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { useAuthSession } from "@/features/auth";
import {
  fetchMyTasks,
  fetchMyTickets,
  getTicketDisplayName,
  type TaskItem,
  type TicketView,
} from "@/features/runtime";
import { fetchOperationalFailures } from "@/features/operations/api";
import { PageHeader } from "@/shared/components/ui/page-header";
import { MetricCard } from "@/shared/components/ui/metric-card";
import { StatusBadge } from "@/shared/components/ui/status-badge";
import { ErrorState } from "@/shared/components/ui/error-state";

export default function HomePage() {
  const { canAccessOperations, actor } = useAuthSession();
  const [tickets, setTickets] = useState<TicketView[]>([]);
  const [tasks, setTasks] = useState<TaskItem[]>([]);
  const [failureCount, setFailureCount] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;

    Promise.all([fetchMyTickets(), fetchMyTasks()])
      .then(([ticketItems, taskItems]) => {
        if (!active) return;
        setTickets(ticketItems);
        setTasks(taskItems);
      })
      .catch((reason: unknown) => {
        if (active) {
          setError(
            reason instanceof Error ? reason.message : "Không thể tải không gian làm việc",
          );
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    if (canAccessOperations) {
      fetchOperationalFailures()
        .then((items) => {
          if (active) setFailureCount(items.length);
        })
        .catch(() => {
          // Health is an optional privileged panel; the primary workspace remains useful.
        });
    }

    return () => {
      active = false;
    };
  }, [canAccessOperations]);

  const activeTickets = useMemo(
    () =>
      tickets.filter(
        (ticket) => !["COMPLETED", "CANCELLED", "REJECTED"].includes(ticket.status),
      ).length,
    [tickets],
  );
  const openTasks = useMemo(
    () =>
      tasks.filter((task) => !["COMPLETED", "CANCELLED", "EXPIRED"].includes(task.status))
        .length,
    [tasks],
  );
  const recentItems = useMemo(
    () =>
      [...tickets]
        .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
        .slice(0, 5),
    [tickets],
  );

  return (
    <div className="space-y-8" data-testid="overview-page">
      <PageHeader
        eyebrow="Không gian làm việc"
        title={`Chào mừng bạn trở lại, ${actor?.principalName?.split(" ")[0] ?? "bạn"}`}
        description="Tổng quan các yêu cầu và công việc đang cần bạn xử lý."
        action={
          <Link
            href="/catalog"
            className="inline-flex items-center gap-2 rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-slate-800"
          >
            Tạo yêu cầu <span aria-hidden="true">↗</span>
          </Link>
        }
      />

      {error ? (
        <ErrorState
          title="Không thể tải dữ liệu không gian làm việc"
          message={error}
          onRetry={() => window.location.reload()}
        />
      ) : (
        <>
          <section
            className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4"
            aria-label="Tóm tắt không gian làm việc"
          >
            <MetricCard
              label="Ticket đang xử lý"
              value={loading ? "—" : activeTickets}
              detail="Các yêu cầu chưa hoàn tất"
              tone="blue"
              icon={<TicketIcon />}
            />
            <MetricCard
              label="Công việc cần xử lý"
              value={loading ? "—" : openTasks}
              detail="Công việc đã giao hoặc đang chờ bạn nhận"
              tone="violet"
              icon={<TaskIcon />}
            />
            <MetricCard
              label="Tổng số ticket"
              value={loading ? "—" : tickets.length}
              detail="Các ticket trong tài khoản của bạn"
              tone="emerald"
              icon={<StackIcon />}
            />
            {canAccessOperations ? (
              <MetricCard
                label="Sự cố vận hành"
                value={loading || failureCount === null ? "—" : failureCount}
                detail="Mục cần được xử lý lại"
                tone="amber"
                icon={<PulseIcon />}
              />
            ) : (
              <MetricCard
                label="Danh mục yêu cầu"
                value="Sẵn sàng"
                detail="Danh mục sẵn sàng"
                tone="amber"
                icon={<PlusIcon />}
              />
            )}
          </section>

          <div className="grid gap-6 xl:grid-cols-[1.4fr_1fr]">
            <section className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm">
              <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4 sm:px-6">
                <div>
                  <h2 className="text-base font-semibold text-slate-950">Ticket gần đây</h2>
                  <p className="mt-1 text-xs text-slate-500">Hoạt động yêu cầu mới nhất</p>
                </div>
                <Link href="/tickets" className="text-xs font-semibold text-blue-700 hover:text-blue-900">
                  Xem tất cả
                </Link>
              </div>
              {loading ? (
                <div className="space-y-3 p-5">
                  <div className="h-12 animate-pulse rounded-xl bg-slate-100" />
                  <div className="h-12 animate-pulse rounded-xl bg-slate-100" />
                </div>
              ) : recentItems.length === 0 ? (
                <div className="p-8 text-center">
                  <p className="text-sm font-semibold text-slate-800">Chưa có ticket</p>
                  <Link href="/catalog" className="mt-4 inline-flex rounded-lg bg-blue-600 px-3 py-2 text-xs font-semibold text-white">
                    Xem danh mục
                  </Link>
                </div>
              ) : (
                <div className="divide-y divide-slate-100">
                  {recentItems.map((ticket) => (
                    <Link
                      key={ticket.id}
                      href={`/tickets/${ticket.id}`}
                      className="flex items-center justify-between gap-4 px-5 py-4 transition hover:bg-slate-50 sm:px-6"
                    >
                      <div className="min-w-0">
                        <p className="truncate text-sm font-semibold text-slate-800">{getTicketDisplayName(ticket)}</p>
                        <p className="mt-1 text-xs text-slate-500">Ticket #{ticket.id.slice(0, 8)} · Cập nhật {formatDate(ticket.updatedAt)}</p>
                      </div>
                      <StatusBadge value={ticket.status} />
                    </Link>
                  ))}
                </div>
              )}
            </section>

            <section className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm sm:p-6">
              <div className="flex items-center justify-between">
                <div>
                  <h2 className="text-base font-semibold text-slate-950">Việc cần làm</h2>
                  <p className="mt-1 text-xs text-slate-500">Tiếp tục xử lý công việc</p>
                </div>
                <Link href="/tasks" className="text-xs font-semibold text-blue-700 hover:text-blue-900">Mở danh sách</Link>
              </div>
              <div className="mt-5 space-y-3">
                <ActionRow href="/catalog" title="Tạo yêu cầu mới" icon={<PlusIcon />} />
                <ActionRow href="/tickets" title="Theo dõi ticket" icon={<TicketIcon />} />
                <ActionRow href="/tasks" title="Xem công việc được giao" icon={<TaskIcon />} />
                {canAccessOperations ? <ActionRow href="/operations" title="Kiểm tra hàng đợi xử lý lại" icon={<PulseIcon />} /> : null}
              </div>
            </section>
          </div>
        </>
      )}
    </div>
  );
}

function ActionRow({
  href,
  title,
  icon,
}: {
  href: string;
  title: string;
  icon: React.ReactNode;
}) {
  return (
    <Link href={href} className="group flex items-center gap-3 rounded-xl border border-slate-100 p-3 transition hover:border-blue-200 hover:bg-blue-50/50">
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-600 transition group-hover:bg-blue-100 group-hover:text-blue-700">{icon}</span>
      <span className="min-w-0 flex-1"><span className="block text-sm font-semibold text-slate-800">{title}</span></span>
      <span className="text-slate-300 transition group-hover:translate-x-0.5 group-hover:text-blue-600" aria-hidden="true">→</span>
    </Link>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("vi-VN", { month: "short", day: "numeric" }).format(new Date(value));
}

function TicketIcon() { return <svg aria-hidden="true" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M4 7.5A2.5 2.5 0 016.5 5h11A2.5 2.5 0 0120 7.5v1a2 2 0 000 4v1a2.5 2.5 0 01-2.5 2.5h-11A2.5 2.5 0 014 13.5v-1a2 2 0 000-4v-1z" /></svg>; }
function TaskIcon() { return <svg aria-hidden="true" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M9 5h6m-7 4h8m-8 4h5m-7 7h10a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" /></svg>; }
function StackIcon() { return <svg aria-hidden="true" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M12 4l8 4-8 4-8-4 8-4zm-8 8l8 4 8-4M4 16l8 4 8-4" /></svg>; }
function PulseIcon() { return <svg aria-hidden="true" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M4 12h3l2-6 4 12 2-6h5" /></svg>; }
function PlusIcon() { return <svg aria-hidden="true" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M12 5v14m-7-7h14" /></svg>; }
