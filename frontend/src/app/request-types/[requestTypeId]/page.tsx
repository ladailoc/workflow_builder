"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { AuthRouteGuard } from "@/features/auth";
import {
  AdminPageHeader,
  LifecycleBadge,
  LoadingPanel,
  RequestTypeForm,
  fetchAdminRequestType,
  fetchWorkflows,
  setRequestTypeActive,
  updateRequestType,
  type RequestTypeAdminView,
  type RequestTypeFormValue,
  type WorkflowSummary,
} from "@/features/workflow-management";
import { ErrorState } from "@/shared/components/ui/error-state";

export default function RequestTypeDetailPage() {
  const { requestTypeId } = useParams<{ requestTypeId: string }>();
  const [item, setItem] = useState<RequestTypeAdminView | null>(null);
  const [workflows, setWorkflows] = useState<WorkflowSummary[]>([]);
  const [form, setForm] = useState<RequestTypeFormValue | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const load = useCallback(
    () =>
      Promise.all([fetchAdminRequestType(requestTypeId), fetchWorkflows()])
        .then(([requestType, workflowPage]) => {
          setItem(requestType);
          setWorkflows(workflowPage.items);
          setForm({
            key: requestType.key,
            name: requestType.name,
            description: requestType.description ?? "",
            category: requestType.category,
            workflowDefinitionId: requestType.workflowDefinitionId,
            active: requestType.active,
          });
        })
        .catch((reason: unknown) =>
          setError(
            reason instanceof Error ? reason.message : "Request Type not found",
          ),
        ),
    [requestTypeId],
  );
  useEffect(() => {
    void load();
  }, [load]);

  if (error && !item)
    return (
      <AuthRouteGuard roles={["WORKFLOW_OWNER", "ADMIN"]}>
        <ErrorState
          title="Request Type unavailable"
          message={error}
          onRetry={() => void load()}
        />
      </AuthRouteGuard>
    );
  if (!item || !form)
    return (
      <AuthRouteGuard roles={["WORKFLOW_OWNER", "ADMIN"]}>
        <LoadingPanel label="Loading Request Type…" />
      </AuthRouteGuard>
    );
  return (
    <AuthRouteGuard roles={["WORKFLOW_OWNER", "ADMIN"]}>
      <div
        className="mx-auto max-w-3xl space-y-6"
        data-testid="request-type-detail-page"
      >
        <AdminPageHeader
          title={item.name}
          description="Business-facing metadata and WorkflowDefinition mapping."
          action={
            <span
              className={`rounded-full px-3 py-1 text-xs font-semibold ${item.active ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-600"}`}
            >
              {item.active ? "ACTIVE" : "INACTIVE"}
            </span>
          }
        />
        <section className="rounded-xl border border-slate-200 bg-white p-5">
          <h2 className="font-semibold text-slate-900">Workflow mapping</h2>
          <div className="mt-3 flex flex-wrap items-center gap-3 text-sm">
            <Link
              href={`/workflows/${item.workflowDefinitionId}`}
              className="font-semibold text-blue-700"
            >
              {item.workflowDefinitionName}
            </Link>
            <LifecycleBadge value={item.workflowLifecycle} />
            <span>
              {item.currentPublishedVersionNo
                ? `Current Published V${item.currentPublishedVersionNo}`
                : "No current Published version"}
            </span>
          </div>
          {(!item.schemaAvailable || item.workflowLifecycle !== "ACTIVE") && (
            <p className="mt-3 rounded-lg bg-amber-50 p-3 text-sm text-amber-800">
              This Request Type is not currently creatable in the end-user
              catalog. Activate the workflow and publish a valid
              TicketFormSchema.
            </p>
          )}
        </section>
        <RequestTypeForm
          editMode
          form={form}
          workflows={workflows}
          saving={saving}
          error={error}
          submitLabel="Save changes"
          onChange={setForm}
          onSubmit={async () => {
            setSaving(true);
            setError(null);
            try {
              await updateRequestType(requestTypeId, item.lockVersion, {
                name: form.name,
                description: form.description,
                category: form.category,
                workflowDefinitionId: form.workflowDefinitionId,
                creationPolicyJson: item.creationPolicyJson,
              });
              await load();
            } catch (reason) {
              setError(
                reason instanceof Error
                  ? reason.message
                  : "Unable to save Request Type",
              );
            } finally {
              setSaving(false);
            }
          }}
        />
        <div className="flex justify-end">
          <button
            disabled={saving}
            onClick={() => {
              if (
                window.confirm(
                  `${item.active ? "Deactivate" : "Activate"} this Request Type?`,
                )
              ) {
                setSaving(true);
                setRequestTypeActive(
                  requestTypeId,
                  !item.active,
                  item.lockVersion,
                  `${item.active ? "Deactivated" : "Activated"} from Request Type Management`,
                )
                  .then(() => load())
                  .catch((reason: unknown) =>
                    setError(
                      reason instanceof Error
                        ? reason.message
                        : "Unable to update activation",
                    ),
                  )
                  .finally(() => setSaving(false));
              }
            }}
            className={`rounded-lg border px-4 py-2 text-sm font-semibold ${item.active ? "border-rose-300 text-rose-700" : "border-emerald-300 text-emerald-700"}`}
          >
            {item.active ? "Deactivate" : "Activate"}
          </button>
        </div>
      </div>
    </AuthRouteGuard>
  );
}
