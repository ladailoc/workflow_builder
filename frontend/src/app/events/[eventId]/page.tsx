"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import {
  fetchEventMonitoring,
  EventDetailView,
  type EventMonitoringView,
} from "@/features/runtime";
import { LoadingState } from "@/shared/components/ui/loading-state";
import { ErrorState } from "@/shared/components/ui/error-state";

interface EventDetailPageProps {
  params: Promise<{
    eventId: string;
  }>;
}

export default function EventDetailPage({ params }: EventDetailPageProps) {
  const { eventId } = use(params);
  const [event, setEvent] = useState<EventMonitoringView | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadEvent = () => {
    setIsLoading(true);
    setError(null);
    fetchEventMonitoring(eventId)
      .then((data) => {
        setEvent(data);
      })
      .catch((err: unknown) => {
        setError(
          err instanceof Error ? err.message : "Failed to load event monitoring",
        );
      })
      .finally(() => {
        setIsLoading(false);
      });
  };

  useEffect(() => {
    let ignore = false;
    fetchEventMonitoring(eventId)
      .then((data) => {
        if (!ignore) {
          setEvent(data);
          setIsLoading(false);
        }
      })
      .catch((err: unknown) => {
        if (!ignore) {
          setError(
            err instanceof Error ? err.message : "Failed to load event monitoring",
          );
          setIsLoading(false);
        }
      });
    return () => {
      ignore = true;
    };
  }, [eventId]);

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2">
        <Link
          href="/events"
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
          <span>Back to Events</span>
        </Link>
      </div>

      {isLoading ? (
        <LoadingState title="Loading event trace..." />
      ) : error ? (
        <ErrorState
          title="Could not load event trace"
          message={error}
          onRetry={loadEvent}
        />
      ) : event ? (
        <EventDetailView event={event} />
      ) : null}
    </div>
  );
}
