"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { fetchMyTickets, type TicketView } from "@/features/runtime";
import { LoadingState } from "@/shared/components/ui/loading-state";
import { ErrorState } from "@/shared/components/ui/error-state";

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
          err instanceof Error ? err.message : "Failed to load ticket records",
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
            err instanceof Error ? err.message : "Failed to load ticket records",
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
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            My Tickets
          </h1>
          <p className="mt-1 text-sm text-slate-500">
            Track and manage your submitted workflow tickets and drafts.
          </p>
        </div>
        <Link
          href="/catalog"
          className="rounded-lg bg-blue-600 px-4 py-2 text-xs font-semibold text-white shadow-2xs hover:bg-blue-700 transition-colors"
        >
          + New Ticket
        </Link>
      </div>

      {isLoading ? (
        <LoadingState title="Loading tickets..." />
      ) : error ? (
        <ErrorState
          title="Could not load tickets"
          message={error}
          onRetry={loadTickets}
        />
      ) : tickets.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 p-12 text-center">
          <h3 className="text-sm font-semibold text-slate-800">
            No tickets found
          </h3>
          <p className="mt-1 text-xs text-slate-500">
            You haven&apos;t submitted any workflow requests yet.
          </p>
          <div className="mt-4">
            <Link
              href="/catalog"
              className="inline-flex rounded-lg bg-blue-600 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-blue-700"
            >
              Browse Catalog
            </Link>
          </div>
        </div>
      ) : (
        <div className="rounded-xl border border-slate-200 bg-white overflow-hidden shadow-xs">
          <table className="w-full text-left text-xs">
            <thead className="border-b border-slate-200 bg-slate-50 text-slate-500 font-semibold">
              <tr>
                <th className="px-4 py-3">Ticket ID</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Revision</th>
                <th className="px-4 py-3">Created</th>
                <th className="px-4 py-3">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {tickets.map((t) => (
                <tr key={t.id} className="hover:bg-slate-50/50 transition-colors">
                  <td className="px-4 py-3 font-mono font-medium text-slate-800">
                    #{t.id.slice(0, 8)}
                  </td>
                  <td className="px-4 py-3">
                    <span className="rounded-full bg-blue-50 px-2 py-0.5 text-[11px] font-semibold text-blue-700">
                      {t.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-slate-600">v{t.dataRevision}</td>
                  <td className="px-4 py-3 text-slate-500">
                    {new Date(t.createdAt).toLocaleDateString()}
                  </td>
                  <td className="px-4 py-3">
                    <Link
                      href={`/tickets/${t.id}`}
                      className="font-semibold text-blue-600 hover:underline"
                    >
                      View Details →
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
