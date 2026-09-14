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
  fetchAdminRequestTypes,
  type RequestTypeAdminView,
} from "@/features/workflow-management";
import { ErrorState } from "@/shared/components/ui/error-state";

export default function RequestTypesPage() {
  const [items, setItems] = useState<RequestTypeAdminView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const load = () => {
    setLoading(true);
    setError(null);
    fetchAdminRequestTypes()
      .then((page) => setItems(page.items))
      .catch((reason: unknown) =>
        setError(
          reason instanceof Error
            ? reason.message
            : "Unable to load Request Types",
        ),
      )
      .finally(() => setLoading(false));
  };
  useEffect(() => {
    let ignore = false;
    fetchAdminRequestTypes()
      .then((page) => {
        if (!ignore) setItems(page.items);
      })
      .catch((reason: unknown) => {
        if (!ignore)
          setError(
            reason instanceof Error
              ? reason.message
              : "Unable to load Request Types",
          );
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });
    return () => {
      ignore = true;
    };
  }, []);

  return (
    <AuthRouteGuard roles={["WORKFLOW_OWNER", "ADMIN"]}>
      <div className="space-y-6" data-testid="request-type-management-page">
        <AdminPageHeader
          title="Request Types"
          description="Manage business catalog entries and their WorkflowDefinition mapping."
          action={
            <PrimaryLink href="/request-types/new">
              + New Request Type
            </PrimaryLink>
          }
        />
        {error ? (
          <ErrorState
            title="Could not load Request Types"
            message={error}
            onRetry={load}
          />
        ) : loading ? (
          <LoadingPanel label="Loading Request Types…" />
        ) : items.length === 0 ? (
          <EmptyPanel
            title="No Request Types"
            detail="Create a business-facing RequestType and map it to a WorkflowDefinition."
          />
        ) : (
          <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 text-left text-sm">
                <thead className="bg-slate-50 text-xs text-slate-500 uppercase">
                  <tr>
                    <th className="px-5 py-3">Name</th>
                    <th className="px-5 py-3">Key</th>
                    <th className="px-5 py-3">Workflow</th>
                    <th className="px-5 py-3">Published</th>
                    <th className="px-5 py-3">Active</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {items.map((item) => (
                    <tr
                      key={item.id}
                      data-testid={`request-type-row-${item.id}`}
                    >
                      <td className="px-5 py-4">
                        <Link
                          href={`/request-types/${item.id}`}
                          className="font-semibold text-blue-700"
                        >
                          {item.name}
                        </Link>
                        <p className="text-xs text-slate-500">
                          {item.category}
                        </p>
                      </td>
                      <td className="px-5 py-4 font-mono text-xs">
                        {item.key}
                      </td>
                      <td className="px-5 py-4">
                        <span className="font-medium">
                          {item.workflowDefinitionName}
                        </span>
                        <span className="ml-2">
                          <LifecycleBadge value={item.workflowLifecycle} />
                        </span>
                      </td>
                      <td className="px-5 py-4">
                        {item.currentPublishedVersionNo ? (
                          `V${item.currentPublishedVersionNo}`
                        ) : (
                          <span className="text-amber-700">
                            No Published schema
                          </span>
                        )}
                      </td>
                      <td className="px-5 py-4">
                        <span
                          className={`rounded-full px-2 py-1 text-xs font-semibold ${item.active ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-600"}`}
                        >
                          {item.active ? "Yes" : "No"}
                        </span>
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
