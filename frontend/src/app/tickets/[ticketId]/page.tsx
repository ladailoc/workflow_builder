"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import {
  fetchTicket,
  TicketDetailView,
  type TicketAggregate,
} from "@/features/runtime";
import { LoadingState } from "@/shared/components/ui/loading-state";
import { ErrorState } from "@/shared/components/ui/error-state";

interface TicketDetailPageProps {
  params: Promise<{
    ticketId: string;
  }>;
}

export default function TicketDetailPage({ params }: TicketDetailPageProps) {
  const { ticketId } = use(params);
  const [aggregate, setAggregate] = useState<TicketAggregate | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadTicket = () => {
    setIsLoading(true);
    setError(null);
    fetchTicket(ticketId)
      .then((data) => {
        setAggregate(data);
      })
      .catch((err: unknown) => {
        setError(
          err instanceof Error ? err.message : "Không thể tải thông tin ticket",
        );
      })
      .finally(() => {
        setIsLoading(false);
      });
  };

  useEffect(() => {
    let ignore = false;
    fetchTicket(ticketId)
      .then((data) => {
        if (!ignore) {
          setAggregate(data);
          setIsLoading(false);
        }
      })
      .catch((err: unknown) => {
        if (!ignore) {
          setError(
            err instanceof Error ? err.message : "Không thể tải thông tin ticket",
          );
          setIsLoading(false);
        }
      });
    return () => {
      ignore = true;
    };
  }, [ticketId]);

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2">
        <Link
          href="/tickets"
          className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-500 hover:text-slate-900 transition-colors"
        >
          <svg
            className="h-3.5 w-3.5"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M10 19l-7-7m0 0l7-7m-7 7h18"
            />
          </svg>
          <span>Quay lại ticket của tôi</span>
        </Link>
      </div>

      {isLoading ? (
        <LoadingState title="Đang tải thông tin ticket…" />
      ) : error ? (
        <ErrorState
          title="Không thể tải ticket"
          message={error}
          onRetry={loadTicket}
        />
      ) : aggregate ? (
        <TicketDetailView aggregate={aggregate} />
      ) : null}
    </div>
  );
}
