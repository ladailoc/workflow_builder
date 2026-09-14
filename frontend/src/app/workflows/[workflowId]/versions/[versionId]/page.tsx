"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { AuthRouteGuard } from "@/features/auth";
import {
  AdminPageHeader,
  LifecycleBadge,
  LoadingPanel,
  fetchWorkflow,
  fetchWorkflowVersion,
  type WorkflowDetail,
  type WorkflowVersionDetail,
} from "@/features/workflow-management";
import { ErrorState } from "@/shared/components/ui/error-state";

export default function WorkflowVersionPage() {
  const { workflowId, versionId } = useParams<{
    workflowId: string;
    versionId: string;
  }>();
  const [workflow, setWorkflow] = useState<WorkflowDetail | null>(null);
  const [detail, setDetail] = useState<WorkflowVersionDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;
    Promise.all([
      fetchWorkflow(workflowId),
      fetchWorkflowVersion(workflowId, versionId),
    ])
      .then(([workflowValue, versionValue]) => {
        if (!ignore) {
          setWorkflow(workflowValue);
          setDetail(versionValue);
        }
      })
      .catch((reason: unknown) => {
        if (!ignore)
          setError(
            reason instanceof Error ? reason.message : "Version not found",
          );
      });
    return () => {
      ignore = true;
    };
  }, [workflowId, versionId]);

  return (
    <AuthRouteGuard
      roles={["WORKFLOW_OWNER", "WORKFLOW_EDITOR", "OPERATOR", "ADMIN"]}
    >
      {error ? (
        <ErrorState title="Workflow version unavailable" message={error} />
      ) : !workflow || !detail ? (
        <LoadingPanel label="Loading version detail…" />
      ) : (
        <div className="space-y-6" data-testid="workflow-version-detail-page">
          <AdminPageHeader
            title={`${workflow.workflow.name} · Version ${detail.version.versionNo}`}
            description="Immutable version metadata and normalized execution contracts."
            action={<LifecycleBadge value={detail.version.status} />}
          />
          {detail.version.status !== "DRAFT" && (
            <div className="rounded-xl border border-blue-200 bg-blue-50 p-4 text-sm text-blue-800">
              This version is immutable. Open the graph in read-only mode or
              clone it as a new Draft from Workflow Detail.
            </div>
          )}
          <div className="grid gap-4 md:grid-cols-3">
            <Card label="Revision" value={String(detail.version.revision)} />
            <Card
              label="Nodes / edges"
              value={`${detail.nodes.length} / ${detail.edges.length}`}
            />
            <Card
              label="Checksum"
              value={detail.version.checksum || "Not published"}
              mono
            />
          </div>
          <div className="flex gap-3">
            <Link
              href={`/workflows/${workflowId}/versions/${versionId}/builder`}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white"
            >
              {detail.version.status === "DRAFT"
                ? "Open Builder"
                : "Inspect graph"}
            </Link>
            <Link
              href={`/workflows/${workflowId}`}
              className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-700"
            >
              Back to history
            </Link>
          </div>
          <section className="rounded-xl border border-slate-200 bg-white p-5">
            <h2 className="font-semibold text-slate-900">
              Execution contracts
            </h2>
            <dl className="mt-4 grid gap-3 text-sm md:grid-cols-2">
              <div>
                <dt className="text-slate-500">Based on</dt>
                <dd className="font-mono text-xs">
                  {detail.version.basedOnVersionId || "—"}
                </dd>
              </div>
              <div>
                <dt className="text-slate-500">Rollback of</dt>
                <dd className="font-mono text-xs">
                  {detail.version.rollbackOfVersionId || "—"}
                </dd>
              </div>
              <div>
                <dt className="text-slate-500">Forms</dt>
                <dd>
                  {detail.forms.map((form) => form.formKey).join(", ") ||
                    "No forms"}
                </dd>
              </div>
              <div>
                <dt className="text-slate-500">Published</dt>
                <dd>
                  {detail.version.publishedAt
                    ? new Date(detail.version.publishedAt).toLocaleString()
                    : "Not published"}
                </dd>
              </div>
            </dl>
          </section>
        </div>
      )}
    </AuthRouteGuard>
  );
}

function Card({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4">
      <p className="text-xs tracking-wide text-slate-400 uppercase">{label}</p>
      <p
        className={`mt-2 truncate text-sm font-semibold text-slate-800 ${mono ? "font-mono text-xs" : ""}`}
      >
        {value}
      </p>
    </div>
  );
}
