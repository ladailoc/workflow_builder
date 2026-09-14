"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { AuthRouteGuard } from "@/features/auth";
import {
  AdminPageHeader,
  RequestTypeForm,
  createRequestType,
  fetchWorkflows,
  type WorkflowSummary,
} from "@/features/workflow-management";

export default function NewRequestTypePage() {
  const router = useRouter();
  const [workflows, setWorkflows] = useState<WorkflowSummary[]>([]);
  const [form, setForm] = useState({
    key: "",
    name: "",
    description: "",
    category: "GENERAL",
    workflowDefinitionId: "",
    active: false,
  });
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    fetchWorkflows()
      .then((page) => setWorkflows(page.items))
      .catch((reason: unknown) =>
        setError(
          reason instanceof Error ? reason.message : "Unable to load workflows",
        ),
      );
  }, []);
  return (
    <AuthRouteGuard roles={["WORKFLOW_OWNER", "ADMIN"]}>
      <div
        className="mx-auto max-w-2xl space-y-6"
        data-testid="create-request-type-page"
      >
        <AdminPageHeader
          title="New Request Type"
          description="Create a business-facing catalog entry mapped to a WorkflowDefinition, never a technical version."
        />
        <RequestTypeForm
          form={form}
          workflows={workflows}
          saving={saving}
          error={error}
          submitLabel="Create Request Type"
          onChange={setForm}
          onSubmit={async () => {
            setSaving(true);
            setError(null);
            try {
              const created = await createRequestType({
                ...form,
                creationPolicyJson: {},
              });
              router.push(`/request-types/${created.id}`);
            } catch (reason) {
              setError(
                reason instanceof Error
                  ? reason.message
                  : "Unable to create Request Type",
              );
            } finally {
              setSaving(false);
            }
          }}
        />
      </div>
    </AuthRouteGuard>
  );
}
