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
          reason instanceof Error ? reason.message : "Không thể tải danh sách quy trình",
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
          title="Tạo loại yêu cầu"
          description="Tạo một mục dễ hiểu trong danh mục yêu cầu và liên kết với WorkflowDefinition, không liên kết trực tiếp với phiên bản kỹ thuật."
        />
        <RequestTypeForm
          form={form}
          workflows={workflows}
          saving={saving}
          error={error}
          submitLabel="Tạo loại yêu cầu"
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
                  : "Không thể tạo loại yêu cầu",
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
