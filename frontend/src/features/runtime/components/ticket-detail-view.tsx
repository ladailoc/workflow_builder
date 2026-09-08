"use client";

import { useState } from "react";
import Link from "next/link";
import type { TicketAggregate } from "../types";

interface TicketDetailViewProps {
  aggregate: TicketAggregate;
}

export function TicketDetailView({ aggregate }: TicketDetailViewProps) {
  const { ticket, revisions, subjects, currentEventId } = aggregate;
  const [activeTab, setActiveTab] = useState<"data" | "history" | "subjects">(
    "data",
  );

  const getStatusBadge = (status: string) => {
    switch (status) {
      case "COMPLETED":
        return "bg-emerald-50 text-emerald-700 border-emerald-200";
      case "REJECTED":
      case "CANCELLED":
        return "bg-rose-50 text-rose-700 border-rose-200";
      case "IN_PROGRESS":
      case "SUBMITTED":
        return "bg-blue-50 text-blue-700 border-blue-200";
      default:
        return "bg-slate-50 text-slate-700 border-slate-200";
    }
  };

  // Find file/attachment references in dataJson
  const attachments = Object.entries(ticket.dataJson || {}).filter(
    ([key, value]) =>
      key.toLowerCase().includes("file") ||
      key.toLowerCase().includes("attachment") ||
      (typeof value === "string" && value.startsWith("file://")),
  );

  return (
    <div className="space-y-6 max-w-5xl" data-testid="ticket-detail-view">
      {/* Header */}
      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="space-y-1">
            <div className="flex items-center gap-2.5">
              <h1 className="text-xl font-bold tracking-tight text-slate-900">
                Ticket #{ticket.id.slice(0, 8)}
              </h1>
              <span
                data-testid="ticket-status-badge"
                className={`rounded-full border px-2.5 py-0.5 text-xs font-semibold ${getStatusBadge(
                  ticket.status,
                )}`}
              >
                {ticket.status}
              </span>
            </div>
            <p className="text-xs text-slate-500">
              Revision: <span className="font-semibold text-slate-700">v{ticket.dataRevision}</span> • Created:{" "}
              {new Date(ticket.createdAt).toLocaleString()}
            </p>
          </div>

          {currentEventId && (
            <Link
              href={`/events/${currentEventId}`}
              data-testid="link-to-event"
              className="inline-flex items-center gap-2 rounded-lg bg-blue-50 px-3.5 py-2 text-xs font-semibold text-blue-700 hover:bg-blue-100 transition-colors"
            >
              <span>View Event Execution</span>
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
                  d="M14 5l7 7m0 0l-7 7m7-7H3"
                />
              </svg>
            </Link>
          )}
        </div>

        {/* Tab Navigation */}
        <div className="mt-6 flex border-b border-slate-200">
          <button
            type="button"
            data-testid="tab-business-data"
            onClick={() => setActiveTab("data")}
            className={`border-b-2 px-4 py-2.5 text-xs font-semibold transition-colors ${
              activeTab === "data"
                ? "border-blue-600 text-blue-600"
                : "border-transparent text-slate-500 hover:text-slate-900"
            }`}
          >
            Business Data
          </button>
          <button
            type="button"
            data-testid="tab-history"
            onClick={() => setActiveTab("history")}
            className={`border-b-2 px-4 py-2.5 text-xs font-semibold transition-colors ${
              activeTab === "history"
                ? "border-blue-600 text-blue-600"
                : "border-transparent text-slate-500 hover:text-slate-900"
            }`}
          >
            Revision History ({revisions.length})
          </button>
          <button
            type="button"
            data-testid="tab-subjects"
            onClick={() => setActiveTab("subjects")}
            className={`border-b-2 px-4 py-2.5 text-xs font-semibold transition-colors ${
              activeTab === "subjects"
                ? "border-blue-600 text-blue-600"
                : "border-transparent text-slate-500 hover:text-slate-900"
            }`}
          >
            Subjects ({subjects.length})
          </button>
        </div>
      </div>

      {/* Tab: Business Data */}
      {activeTab === "data" && (
        <div className="space-y-6">
          <div
            data-testid="business-data-section"
            className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs space-y-4"
          >
            <h3 className="text-sm font-semibold text-slate-900">
              Submitted Fields
            </h3>
            {Object.keys(ticket.dataJson || {}).length === 0 ? (
              <p className="text-xs text-slate-400">No form fields recorded.</p>
            ) : (
              <dl className="grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-2">
                {Object.entries(ticket.dataJson).map(([key, value]) => (
                  <div
                    key={key}
                    data-testid={`data-field-${key}`}
                    className="border-b border-slate-100 pb-3"
                  >
                    <dt className="text-xs font-medium text-slate-500">{key}</dt>
                    <dd className="mt-1 text-sm font-semibold text-slate-800">
                      {typeof value === "object" && value !== null
                        ? JSON.stringify(value)
                        : String(value ?? "")}
                    </dd>
                  </div>
                ))}
              </dl>
            )}
          </div>

          {/* Attachments Section */}
          <div
            data-testid="attachments-section"
            className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs space-y-3"
          >
            <h3 className="text-sm font-semibold text-slate-900">
              Attachments & File References
            </h3>
            {attachments.length === 0 ? (
              <p className="text-xs text-slate-400">No attachments uploaded.</p>
            ) : (
              <ul className="divide-y divide-slate-100">
                {attachments.map(([key, val]) => (
                  <li
                    key={key}
                    data-testid={`attachment-item-${key}`}
                    className="flex items-center justify-between py-2.5"
                  >
                    <div className="flex items-center gap-2">
                      <svg
                        className="h-4 w-4 text-blue-500"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13"
                        />
                      </svg>
                      <span className="text-xs font-medium text-slate-700">
                        {key}
                      </span>
                    </div>
                    <span className="text-xs font-mono text-slate-500">
                      {String(val)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}

      {/* Tab: Revision History Timeline */}
      {activeTab === "history" && (
        <div
          data-testid="revision-history-section"
          className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs space-y-4"
        >
          <h3 className="text-sm font-semibold text-slate-900">
            Revision Timeline
          </h3>
          <div className="relative border-l-2 border-slate-200 pl-4 space-y-6">
            {revisions.map((rev) => (
              <div
                key={rev.id}
                data-testid={`revision-entry-${rev.revisionNo}`}
                className="relative space-y-1"
              >
                <div className="absolute -left-[21px] top-1 h-2.5 w-2.5 rounded-full border-2 border-white bg-blue-600 shadow-xs" />
                <div className="flex items-center gap-2">
                  <span className="text-xs font-bold text-slate-800">
                    Revision #{rev.revisionNo}
                  </span>
                  <span className="text-[11px] text-slate-400">
                    {new Date(rev.submittedAt).toLocaleString()}
                  </span>
                </div>
                {rev.changeReason && (
                  <p className="text-xs text-slate-600 italic">
                    &quot;{rev.changeReason}&quot;
                  </p>
                )}
                <div className="mt-2 rounded-lg bg-slate-50 p-2.5 font-mono text-[11px] text-slate-700">
                  {JSON.stringify(rev.dataSnapshotJson, null, 2)}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Tab: Subjects */}
      {activeTab === "subjects" && (
        <div
          data-testid="subjects-section"
          className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs space-y-4"
        >
          <h3 className="text-sm font-semibold text-slate-900">
            Linked Subjects
          </h3>
          {subjects.length === 0 ? (
            <p className="text-xs text-slate-400">No subjects linked to this ticket.</p>
          ) : (
            <table className="w-full text-left text-xs">
              <thead className="border-b border-slate-200 text-slate-400">
                <tr>
                  <th className="py-2">Subject Type</th>
                  <th className="py-2">Role Key</th>
                  <th className="py-2">Source Field</th>
                  <th className="py-2">Ref ID</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {subjects.map((sub) => (
                  <tr key={sub.id} data-testid={`subject-row-${sub.id}`}>
                    <td className="py-2 font-medium text-slate-800">
                      {sub.subjectType}
                    </td>
                    <td className="py-2 text-slate-600">{sub.roleKey}</td>
                    <td className="py-2 text-slate-600">{sub.sourceField}</td>
                    <td className="py-2 font-mono text-slate-500">
                      {sub.subjectRefId.slice(0, 8)}...
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
