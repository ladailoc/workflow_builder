"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AuthRouteGuard } from "@/features/auth";
import {
  AdminPageHeader,
  EmptyPanel,
  LifecycleBadge,
  LoadingPanel,
  PrimaryLink,
  fetchWorkflows,
  type WorkflowSummary,
} from "@/features/workflow-management";
import { ErrorState } from "@/shared/components/ui/error-state";

export default function WorkflowsPage() {
  const [items, setItems] = useState<WorkflowSummary[]>([]);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = (search = query) => {
    setLoading(true);
    setError(null);
    fetchWorkflows(search)
      .then((page) => setItems(page.items))
      .catch((reason: unknown) =>
        setError(
          reason instanceof Error ? reason.message : "Unable to load workflows",
        ),
      )
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    let ignore = false;
    fetchWorkflows()
      .then((page) => {
        if (!ignore) setItems(page.items);
      })
      .catch((reason: unknown) => {
        if (!ignore) {
          setError(
            reason instanceof Error
              ? reason.message
              : "Unable to load workflows",
          );
        }
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });
    return () => {
      ignore = true;
    };
  }, []);

  return (
    <AuthRouteGuard
      roles={["WORKFLOW_OWNER", "WORKFLOW_EDITOR", "OPERATOR", "ADMIN"]}
    >
      <div className="space-y-6" data-testid="workflow-management-page">
        <AdminPageHeader
          title="Workflows"
          description="Manage workflow definitions, drafts, immutable releases, and version history."
          action={
            <PrimaryLink href="/workflows/new">+ New Workflow</PrimaryLink>
          }
        />

        <form
          className="flex max-w-xl gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            load(query);
          }}
        >
          <input
            aria-label="Search workflows"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search by workflow name or stable key"
            className="min-w-0 flex-1 rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm"
          />
          <button className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50">
            Search
          </button>
        </form>

        {error ? (
          <ErrorState
            title="Could not load workflows"
            message={error}
            onRetry={() => load()}
          />
        ) : loading ? (
          <LoadingPanel label="Loading workflow definitions…" />
        ) : items.length === 0 ? (
          <EmptyPanel
            title="No workflow definitions found"
            detail="Create a WorkflowDefinition, then create a Draft version to open the Builder."
          />
        ) : (
          <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 text-left text-sm">
                <thead className="bg-slate-50 text-xs tracking-wide text-slate-500 uppercase">
                  <tr>
                    <th className="px-5 py-3">Name</th>
                    <th className="px-5 py-3">Key</th>
                    <th className="px-5 py-3">Published</th>
                    <th className="px-5 py-3">Draft</th>
                    <th className="px-5 py-3">Lifecycle</th>
                    <th className="px-5 py-3">Updated</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {items.map((workflow) => (
                    <tr
                      key={workflow.id}
                      data-testid={`workflow-row-${workflow.id}`}
                      className="hover:bg-slate-50/70"
                    >
                      <td className="px-5 py-4">
                        <Link
                          href={`/workflows/${workflow.id}`}
                          className="font-semibold text-blue-700 hover:text-blue-900"
                        >
                          {workflow.name}
                        </Link>
                        <p className="mt-0.5 max-w-sm truncate text-xs text-slate-500">
                          {workflow.description || "No description"}
                        </p>
                      </td>
                      <td className="px-5 py-4 font-mono text-xs text-slate-600">
                        {workflow.key}
                      </td>
                      <td className="px-5 py-4 font-semibold text-slate-700">
                        {workflow.currentPublishedVersionNo
                          ? `V${workflow.currentPublishedVersionNo}`
                          : "—"}
                      </td>
                      <td className="px-5 py-4 font-semibold text-slate-700">
                        {workflow.activeDraftVersionNo
                          ? `V${workflow.activeDraftVersionNo}`
                          : "—"}
                      </td>
                      <td className="px-5 py-4">
                        <LifecycleBadge value={workflow.lifecycle} />
                      </td>
                      <td className="px-5 py-4 text-xs text-slate-500">
                        {new Date(workflow.updatedAt).toLocaleString()}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </AuthRouteGuard>
  );
}
