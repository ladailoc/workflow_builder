"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { apiGet } from "@/shared/api/client";
import { LoadingState } from "@/shared/components/ui/loading-state";
import { ErrorState } from "@/shared/components/ui/error-state";

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
          err instanceof Error ? err.message : "Failed to load events",
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
            err instanceof Error ? err.message : "Failed to load events",
          );
          setIsLoading(false);
        }
      });
    return () => {
      ignore = true;
    };
  }, []);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">
          Events & History
        </h1>
        <p className="mt-1 text-sm text-slate-500">
          Workflow instance execution traces, node progress, and timeline events.
        </p>
      </div>

      {isLoading ? (
        <LoadingState title="Loading events..." />
      ) : error ? (
        <ErrorState
          title="Could not load events"
          message={error}
          onRetry={loadEvents}
        />
      ) : events.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 p-12 text-center text-xs text-slate-500">
          No events currently active or recorded.
        </div>
      ) : (
        <div className="rounded-xl border border-slate-200 bg-white overflow-hidden shadow-xs">
          <table className="w-full text-left text-xs">
            <thead className="border-b border-slate-200 bg-slate-50 text-slate-500 font-semibold">
              <tr>
                <th className="px-4 py-3">Event ID</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Outcome</th>
                <th className="px-4 py-3">Ticket Ref</th>
                <th className="px-4 py-3">Created</th>
                <th className="px-4 py-3">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {events.map((e) => (
                <tr key={e.id} className="hover:bg-slate-50/50 transition-colors">
                  <td className="px-4 py-3 font-mono font-medium text-slate-800">
                    #{e.id.slice(0, 8)}
                  </td>
                  <td className="px-4 py-3">
                    <span className="rounded-full bg-blue-50 px-2 py-0.5 text-[11px] font-semibold text-blue-700">
                      {e.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-slate-600">{e.outcome || "—"}</td>
                  <td className="px-4 py-3 font-mono text-slate-500">
                    #{e.ticketId.slice(0, 8)}
                  </td>
                  <td className="px-4 py-3 text-slate-500">
                    {new Date(e.createdAt).toLocaleDateString()}
                  </td>
                  <td className="px-4 py-3">
                    <Link
                      href={`/events/${e.id}`}
                      className="font-semibold text-blue-600 hover:underline"
                    >
                      Monitoring View →
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
