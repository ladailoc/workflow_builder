"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { EventMonitoringView } from "@/features/runtime/types";
import {
  executeOperationalCommand,
  fetchOperationalEvent,
  fetchOperationalFailures,
} from "../api";
import type { OperationalFailure, OperatorCommand } from "../types";

type Action = "retry" | "resolve" | "manual-task" | "terminate";

export function OperatorConsole() {
  const [failures, setFailures] = useState<OperationalFailure[]>([]);
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [selected, setSelected] = useState<EventMonitoringView | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setFailures(await fetchOperationalFailures());
      setMessage(null);
    } catch (error) {
      setMessage(
        error instanceof Error ? error.message : "Unable to load failures",
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let active = true;
    void fetchOperationalFailures()
      .then((items) => {
        if (active) {
          setFailures(items);
          setMessage(null);
        }
      })
      .catch((error: unknown) => {
        if (active) {
          setMessage(
            error instanceof Error ? error.message : "Unable to load failures",
          );
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const inspect = async (failure: OperationalFailure) => {
    if (!failure.eventId) return;
    setWorking(`inspect:${failure.id}`);
    try {
      setSelected(await fetchOperationalEvent(failure.eventId));
    } catch (error) {
      setMessage(
        error instanceof Error ? error.message : "Unable to load event",
      );
    } finally {
      setWorking(null);
    }
  };

  const run = async (failure: OperationalFailure, action: Action) => {
    if (!reason.trim()) {
      setMessage("A reason is required for every operator override.");
      return;
    }
    const target = endpoint(failure, action);
    if (!target) return;
    const expectedVersion =
      action === "terminate" ? failure.eventVersion : failure.lockVersion;
    if (expectedVersion == null) {
      setMessage("Reload the event before applying this recovery action.");
      return;
    }
    setWorking(`${action}:${failure.id}`);
    const command: OperatorCommand = {
      reason: reason.trim(),
      ...(action === "resolve" ? { outcomePort: "SUCCESS" as const } : {}),
    };
    try {
      await executeOperationalCommand(target, expectedVersion, command);
      setMessage(`Recovery action ${action} accepted.`);
      await load();
    } catch (error) {
      setMessage(
        error instanceof Error ? error.message : "Recovery action failed",
      );
    } finally {
      setWorking(null);
    }
  };

  return (
    <div className="space-y-6" data-testid="operator-console">
      <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
        <label
          className="block text-sm font-semibold text-slate-800"
          htmlFor="override-reason"
        >
          Override reason
        </label>
        <textarea
          id="override-reason"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="Incident or recovery justification"
          className="mt-2 min-h-20 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
        />
        {message ? (
          <p className="mt-2 text-sm text-slate-700" role="status">
            {message}
          </p>
        ) : null}
      </section>

      <section className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
        <div className="border-b border-slate-200 px-5 py-4">
          <h2 className="font-semibold text-slate-900">
            Operational failure queue
          </h2>
        </div>
        {loading ? (
          <p className="p-5 text-sm text-slate-500">Loading failures…</p>
        ) : null}
        {!loading && failures.length === 0 ? (
          <p className="p-5 text-sm text-emerald-700">
            No operational failures.
          </p>
        ) : null}
        {failures.map((failure) => (
          <article
            key={`${failure.category}:${failure.id}`}
            className="border-b border-slate-100 p-5 last:border-0"
          >
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="text-xs font-semibold tracking-wide text-rose-600 uppercase">
                  {failure.category}
                </p>
                <h3 className="font-semibold text-slate-900">{failure.kind}</h3>
                <p className="font-mono text-xs text-slate-500">
                  {failure.aggregateType}:{failure.aggregateId}
                </p>
                <p className="mt-1 text-xs text-slate-500">
                  Attempts {failure.attempts ?? "—"}/
                  {failure.maxAttempts ?? "—"}
                </p>
              </div>
              <span className="rounded-full bg-rose-50 px-2 py-1 text-xs font-medium text-rose-700">
                {failure.status}
              </span>
            </div>
            <pre
              className="mt-3 max-h-28 overflow-auto rounded-lg bg-slate-950 p-3 text-xs text-slate-100"
              data-testid="sanitized-error"
            >
              {JSON.stringify(failure.error ?? { code: "NO_DETAIL" }, null, 2)}
            </pre>
            <div className="mt-3 flex flex-wrap gap-2">
              {retryable(failure) ? (
                <ActionButton
                  label="Retry"
                  busy={working === `retry:${failure.id}`}
                  onClick={() => void run(failure, "retry")}
                />
              ) : null}
              {failure.category === "INTEGRATION_EXECUTION" &&
              failure.status === "MANUAL_RECONCILIATION" ? (
                <>
                  <ActionButton
                    label="Resolve manually"
                    busy={working === `resolve:${failure.id}`}
                    onClick={() => void run(failure, "resolve")}
                  />
                  <ActionButton
                    label="Create manual task"
                    busy={working === `manual-task:${failure.id}`}
                    onClick={() => void run(failure, "manual-task")}
                  />
                </>
              ) : null}
              {failure.eventId ? (
                <ActionButton
                  label="Inspect timeline"
                  busy={working === `inspect:${failure.id}`}
                  onClick={() => void inspect(failure)}
                />
              ) : null}
              {failure.eventId && failure.eventVersion != null ? (
                <ActionButton
                  label="Terminate event"
                  danger
                  busy={working === `terminate:${failure.id}`}
                  onClick={() => void run(failure, "terminate")}
                />
              ) : null}
            </div>
          </article>
        ))}
      </section>

      {selected ? <EventTechnicalInspector event={selected} /> : null}
    </div>
  );
}

function EventTechnicalInspector({ event }: { event: EventMonitoringView }) {
  const counts = useMemo(() => {
    const values = new Map<string, number>();
    for (const occurrence of event.nodeExecutions) {
      values.set(
        occurrence.nodeDefinitionId,
        (values.get(occurrence.nodeDefinitionId) ?? 0) + 1,
      );
    }
    return values;
  }, [event.nodeExecutions]);

  return (
    <section
      className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs"
      data-testid="event-technical-inspector"
    >
      <h2 className="font-semibold text-slate-900">
        Exact execution graph · Version #{event.workflowVersion.versionNo}
      </h2>
      <p className="mt-1 font-mono text-xs text-slate-500">
        {event.workflowVersion.id} · {event.workflowVersion.checksum}
      </p>
      <div className="mt-4 grid gap-3 md:grid-cols-2">
        {event.graph.nodes.map((node) => {
          const occurrences = event.nodeExecutions.filter(
            (item) => item.nodeDefinitionId === node.id,
          );
          return (
            <div
              key={node.id}
              className="rounded-lg border border-slate-200 p-3"
            >
              <p className="font-semibold text-slate-800">
                {node.key}{" "}
                <span className="text-xs font-normal text-slate-500">
                  {node.type}
                </span>
              </p>
              <p className="text-xs text-slate-500">
                {counts.get(node.id) ?? 0} occurrence(s)
              </p>
              {occurrences.map((item) => (
                <p
                  key={item.id}
                  className="mt-1 rounded bg-slate-50 px-2 py-1 font-mono text-xs"
                  data-testid="node-occurrence"
                >
                  {item.status} · cycle {item.cycleId ?? "—"} · path{" "}
                  {item.path ?? "—"} · item {item.item ?? "—"}
                </p>
              ))}
            </div>
          );
        })}
      </div>
      <p className="mt-4 text-xs text-slate-500">
        {event.graph.edges.length} immutable edge definition(s)
      </p>
      <h3 className="mt-5 font-semibold text-slate-800">Timeline</h3>
      <ol className="mt-2 space-y-1 text-sm text-slate-600">
        {event.timeline.map((entry) => (
          <li key={`${entry.type}:${entry.id}:${entry.at}`}>
            {entry.at} · {entry.type} · {entry.state}
          </li>
        ))}
      </ol>
      <h3 className="mt-5 font-semibold text-slate-800">Safe context</h3>
      <pre
        className="mt-2 max-h-72 overflow-auto rounded-lg bg-slate-950 p-3 text-xs text-slate-100"
        data-testid="masked-context"
      >
        {JSON.stringify(event.maskedContext, null, 2)}
      </pre>
    </section>
  );
}

function ActionButton({
  label,
  onClick,
  busy,
  danger = false,
}: {
  label: string;
  onClick: () => void;
  busy: boolean;
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      disabled={busy}
      onClick={onClick}
      className={`rounded-lg border px-3 py-1.5 text-xs font-medium disabled:opacity-50 ${danger ? "border-rose-300 text-rose-700" : "border-slate-300 text-slate-700"}`}
    >
      {busy ? "Working…" : label}
    </button>
  );
}

function retryable(failure: OperationalFailure): boolean {
  return (
    failure.category === "WORKFLOW_JOB" ||
    failure.category === "NODE_EXECUTION" ||
    (failure.category === "INTEGRATION_EXECUTION" &&
      failure.status === "FAILED")
  );
}

function endpoint(
  failure: OperationalFailure,
  action: Action,
): `/${string}` | null {
  if (action === "terminate" && failure.eventId)
    return `/api/v1/events/${encodeURIComponent(failure.eventId)}/terminate`;
  if (failure.category === "WORKFLOW_JOB" && action === "retry")
    return `/api/v1/operations/jobs/${encodeURIComponent(failure.id)}/retry`;
  if (failure.category === "NODE_EXECUTION" && action === "retry")
    return `/api/v1/node-executions/${encodeURIComponent(failure.id)}/retry`;
  if (failure.category === "INTEGRATION_EXECUTION") {
    if (action === "retry")
      return `/api/v1/integrations/${encodeURIComponent(failure.id)}/retry`;
    if (action === "resolve")
      return `/api/v1/integrations/${encodeURIComponent(failure.id)}/resolve`;
    if (action === "manual-task")
      return `/api/v1/integrations/${encodeURIComponent(failure.id)}/manual-task`;
  }
  return null;
}
