"use client";

import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { AuthRouteGuard, useAuthSession } from "@/features/auth";
import {
  WorkflowBuilder,
  type FormSchema,
  type ValidationIssue,
  type WorkflowVersionDto,
} from "@/features/workflow-builder";
import {
  cloneVersionAsDraft,
  fetchWorkflow,
  fetchWorkflowVersion,
  publishWorkflow,
  saveTicketForm,
  saveWorkflowGraph,
  toBackendEdges,
  toBackendNodes,
  toBuilderForm,
  toBuilderVersion,
  toCanonicalForm,
  validateWorkflow,
  type WorkflowDetail,
} from "@/features/workflow-management";
import { ErrorState } from "@/shared/components/ui/error-state";

export default function VersionBuilderPage() {
  const { workflowId, versionId } = useParams<{
    workflowId: string;
    versionId: string;
  }>();
  const router = useRouter();
  const { hasRole } = useAuthSession();
  const canEdit = hasRole(["WORKFLOW_OWNER", "WORKFLOW_EDITOR", "ADMIN"]);
  const canPublish = hasRole(["WORKFLOW_OWNER", "ADMIN"]);
  const [workflow, setWorkflow] = useState<WorkflowDetail | null>(null);
  const [version, setVersion] = useState<WorkflowVersionDto | null>(null);
  const [initialForm, setInitialForm] = useState<FormSchema | null>(null);
  const [lockVersion, setLockVersion] = useState(0);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;
    Promise.all([
      fetchWorkflow(workflowId),
      fetchWorkflowVersion(workflowId, versionId),
    ])
      .then(([workflowValue, versionValue]) => {
        if (ignore) return;
        setWorkflow(workflowValue);
        setVersion(toBuilderVersion(versionValue));
        setInitialForm(toBuilderForm(versionValue));
        setLockVersion(versionValue.version.lockVersion);
        setRevision(versionValue.version.revision);
      })
      .catch((reason: unknown) => {
        if (!ignore)
          setError(
            reason instanceof Error
              ? reason.message
              : "Không thể mở trình xây dựng",
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
        <ErrorState title="Không thể tải trình xây dựng" message={error} />
      ) : !workflow || !version || !initialForm ? (
        <div
          data-testid="builder-loading"
          className="p-12 text-center text-sm text-slate-500"
        >
          Đang tải trình xây dựng theo phiên bản…
        </div>
      ) : (
        <div className="-m-6 md:-m-8" data-testid="version-contextual-builder">
          <div className="border-b border-slate-200 bg-white px-5 py-2 text-xs text-slate-500">
            <button
              onClick={() => router.push("/workflows")}
              className="hover:text-blue-700"
            >
              Quy trình
            </button>
            <span className="px-2">›</span>
            <button
              onClick={() => router.push(`/workflows/${workflowId}`)}
              className="hover:text-blue-700"
            >
              {workflow.workflow.name}
            </button>
            <span className="px-2">›</span>
            <span>Phiên bản {version.versionNo}</span>
            <span className="px-2">›</span>
            <span className="font-semibold text-slate-800">Trình xây dựng</span>
          </div>
          <WorkflowBuilder
            workflowName={workflow.workflow.name}
            readOnly={!canEdit}
            publishAllowed={canPublish}
            initialVersion={version}
            initialRequestForm={initialForm}
            historicalVersions={[]}
            onOpenHistory={() =>
              router.push(`/workflows/${workflowId}#version-history`)
            }
            onSave={async (nodes, edges, requestForm) => {
              if (version.status !== "DRAFT")
                throw new Error("Chỉ phiên bản bản nháp mới có thể chỉnh sửa");
              const graphResult = await saveWorkflowGraph(
                workflowId,
                versionId,
                lockVersion,
                revision,
                toBackendNodes(versionId, nodes),
                toBackendEdges(versionId, edges),
              );
              const formResult = await saveTicketForm(
                workflowId,
                versionId,
                graphResult.draft.lockVersion,
                graphResult.draft.revision,
                toCanonicalForm(requestForm),
              );
              setLockVersion(formResult.draft.lockVersion);
              setRevision(formResult.draft.revision);
            }}
            onSaveSuccess={() => {
              window.setTimeout(
                () => router.push(`/workflows/${workflowId}`),
                900,
              );
            }}
            onValidate={async () => {
              const result = await validateWorkflow(workflowId, versionId);
              return result.issues.map<ValidationIssue>((issue, index) => ({
                id: `${issue.code}-${index}`,
                code: issue.code,
                nodeId:
                  issue.resourceType === "NODE" ? issue.resourceId : undefined,
                nodeLabel:
                  issue.resourceType === "NODE"
                    ? version.nodes.find((node) => node.id === issue.resourceId)
                        ?.data.label
                    : undefined,
                field: issue.fieldPath,
                severity: issue.severity === "ERROR" ? "ERROR" : "WARNING",
                message: issue.message,
              }));
            }}
            onPublish={async () => {
              const published = await publishWorkflow(
                workflowId,
                versionId,
                lockVersion,
                revision,
              );
              setVersion((current) =>
                current ? { ...current, status: "PUBLISHED" } : current,
              );
              return published.workflowVersionId;
            }}
            onPublishSuccess={(publishedVersionId) => {
              if (!publishedVersionId) return;
              window.setTimeout(
                () =>
                  router.push(
                    `/workflows/${workflowId}/versions/${publishedVersionId}`,
                  ),
                1700,
              );
            }}
            onCloneAsNewDraft={(source) => {
              void cloneVersionAsDraft(
                workflowId,
                source.id,
                workflow.workflow.lockVersion,
              )
                .then((clone) =>
                  router.push(
                    `/workflows/${workflowId}/versions/${clone.draftVersionId}/builder`,
                  ),
                )
                .catch((reason: unknown) =>
                  setError(
                    reason instanceof Error
                      ? reason.message
                      : "Không thể sao chép phiên bản",
                  ),
                );
            }}
          />
        </div>
      )}
    </AuthRouteGuard>
  );
}
