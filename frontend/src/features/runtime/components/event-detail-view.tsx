"use client";

import Link from "next/link";
import { useAuthSession } from "@/features/auth";
import type { EventMonitoringView } from "../types";

interface EventDetailViewProps {
  event: EventMonitoringView;
}

export function EventDetailView({ event }: EventDetailViewProps) {
  const { canAccessWorkflowBuilder, canAccessOperations } = useAuthSession();
  const isPrivileged = canAccessWorkflowBuilder || canAccessOperations;

  const getStatusBadge = (status: string) => {
    switch (status) {
      case "COMPLETED":
        return "bg-emerald-50 text-emerald-700 border-emerald-200";
      case "FAILED":
      case "TERMINATED":
      case "CANCELLED":
        return "bg-rose-50 text-rose-700 border-rose-200";
      case "RUNNING":
      case "WAITING":
        return "bg-blue-50 text-blue-700 border-blue-200";
      default:
        return "bg-slate-50 text-slate-700 border-slate-200";
    }
  };

  return (
    <div className="space-y-6 max-w-5xl" data-testid="event-detail-view">
      {/* Event Header */}
      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div className="space-y-1">
            <div className="flex items-center gap-2.5">
              <h1 className="text-xl font-bold tracking-tight text-slate-900">
                Event #{event.eventId.slice(0, 8)}
              </h1>
              <span
                data-testid="event-status-badge"
                className={`rounded-full border px-2.5 py-0.5 text-xs font-semibold ${getStatusBadge(
                  event.status,
                )}`}
              >
                {event.status}
              </span>
              {event.outcome && (
                <span
                  data-testid="event-outcome-badge"
                  className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-semibold text-slate-700"
                >
                  Outcome: {event.outcome}
                </span>
              )}
            </div>
            <p className="text-xs text-slate-500">
              Workflow Version:{" "}
              <span className="font-semibold text-slate-700">
                v{event.workflowVersion.versionNo}
              </span>{" "}
              • Ticket:{" "}
              <Link
                href={`/tickets/${event.ticketId}`}
                className="text-blue-600 hover:underline"
              >
                #{event.ticketId.slice(0, 8)}
              </Link>
            </p>
          </div>
        </div>
      </div>

      {/* Active & Completed Node Executions */}
      <div
        data-testid="nodes-section"
        className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs space-y-4"
      >
        <h3 className="text-sm font-semibold text-slate-900">
          Node Execution Progress
        </h3>
        <div className="divide-y divide-slate-100">
          {event.nodeExecutions.map((node) => (
            <div
              key={node.id}
              data-testid={`node-occurrence-${node.id}`}
              className="flex items-center justify-between py-3"
            >
              <div className="space-y-0.5">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-bold text-slate-800">
                    {node.nodeName || `Node ${node.nodeDefinitionId.slice(0, 8)}`}
                  </span>
                  <span
                    className={`rounded px-1.5 py-0.2 text-[10px] font-semibold ${
                      node.status === "COMPLETED"
                        ? "bg-emerald-50 text-emerald-700"
                        : node.status === "RUNNING"
                          ? "bg-blue-50 text-blue-700 animate-pulse"
                          : "bg-slate-100 text-slate-600"
                    }`}
                  >
                    {node.status}
                  </span>
                </div>
                <p className="text-[11px] text-slate-400">
                  Iteration: {node.iteration} • Outcome: {node.outcomePort || "—"}
                </p>
              </div>

              {/* SubWorkflow Child Event Link */}
              {node.childEventId && (
                <Link
                  href={`/events/${node.childEventId}`}
                  data-testid={`link-child-event-${node.childEventId}`}
                  className="inline-flex items-center gap-1.5 rounded-md bg-purple-50 px-2.5 py-1 text-xs font-semibold text-purple-700 hover:bg-purple-100"
                >
                  <span>Child SubWorkflow</span>
                  <svg
                    className="h-3 w-3"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"
                    />
                  </svg>
                </Link>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Current Assignees and Due Dates */}
      <div
        data-testid="tasks-summary-section"
        className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs space-y-4"
      >
        <h3 className="text-sm font-semibold text-slate-900">
          Tasks & Assignees
        </h3>
        {event.tasks.length === 0 ? (
          <p className="text-xs text-slate-400">No human tasks for this event.</p>
        ) : (
          <table className="w-full text-left text-xs">
            <thead className="border-b border-slate-200 text-slate-400">
              <tr>
                <th className="py-2">Task ID</th>
                <th className="py-2">Status</th>
                <th className="py-2">Assignee</th>
                <th className="py-2">Due Date</th>
                <th className="py-2">Completed</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {event.tasks.map((task) => (
                <tr key={task.id} data-testid={`task-row-${task.id}`}>
                  <td className="py-2 font-mono text-slate-600">
                    #{task.id.slice(0, 8)}
                  </td>
                  <td className="py-2 font-semibold text-slate-800">
                    {task.status}
                  </td>
                  <td className="py-2 text-slate-700">
                    {task.assigneeName || task.assigneeId?.slice(0, 8) || "Unassigned"}
                  </td>
                  <td className="py-2 text-slate-500">
                    {task.dueAt ? new Date(task.dueAt).toLocaleDateString() : "—"}
                  </td>
                  <td className="py-2 text-slate-500">
                    {task.completedAt
                      ? new Date(task.completedAt).toLocaleDateString()
                      : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Execution Timeline */}
      <div
        data-testid="timeline-section"
        className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs space-y-4"
      >
        <h3 className="text-sm font-semibold text-slate-900">
          Execution Timeline
        </h3>
        <div className="relative border-l-2 border-slate-200 pl-4 space-y-5">
          {event.timeline.map((entry, idx) => (
            <div
              key={`${entry.id}-${idx}`}
              data-testid={`timeline-entry-${idx}`}
              className="relative space-y-0.5"
            >
              <div className="absolute -left-[21px] top-1 h-2.5 w-2.5 rounded-full border-2 border-white bg-blue-600 shadow-xs" />
              <div className="flex items-center gap-2">
                <span className="rounded bg-slate-100 px-1.5 py-0.2 text-[10px] font-bold text-slate-600 uppercase">
                  {entry.type}
                </span>
                <span className="text-xs font-semibold text-slate-800">
                  {entry.state}
                </span>
                <span className="text-[11px] text-slate-400">
                  {new Date(entry.at).toLocaleString()}
                </span>
              </div>
              <p className="text-[11px] font-mono text-slate-500">
                Ref: #{entry.id.slice(0, 8)}
              </p>
            </div>
          ))}
        </div>
      </div>

      {/* Privileged Technical Graph / Raw Execution Context Section */}
      {isPrivileged ? (
        <div
          data-testid="privileged-graph-section"
          className="rounded-xl border border-blue-200 bg-blue-50/50 p-6 shadow-xs space-y-3"
        >
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-blue-900">
              Technical Execution Context (Operator / Admin)
            </h3>
            <span className="rounded bg-blue-100 px-2 py-0.5 text-[10px] font-bold text-blue-800 uppercase">
              Privileged
            </span>
          </div>
          <p className="text-xs text-blue-700">
            Internal DAG routing decisions and masked execution context.
          </p>
          <pre
            data-testid="technical-context-json"
            className="rounded-lg bg-white p-3 font-mono text-xs text-slate-800 border border-slate-200 overflow-x-auto"
          >
            {JSON.stringify(event.maskedContext || {}, null, 2)}
          </pre>
        </div>
      ) : (
        <div
          data-testid="unprivileged-hidden-notice"
          className="rounded-xl border border-dashed border-slate-200 p-4 text-center text-xs text-slate-400"
        >
          Detailed technical DAG graph is restricted to Operators and Administrators.
        </div>
      )}
    </div>
  );
}
