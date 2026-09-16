"use client";

import { useEffect, useMemo, useState } from "react";
import { AuthRouteGuard } from "@/features/auth";
import { fetchWorkflows } from "@/features/workflow-management/api";
import { apiDelete, apiGet, apiPost, apiPut } from "@/shared/api/client";
import { PageHeader } from "@/shared/components/ui/page-header";
import { StatusBadge } from "@/shared/components/ui/status-badge";

type SourceType =
  "FORM_FIELD" | "SYSTEM_CONTEXT" | "CONSTANT" | "EXPRESSION" | "DEFAULT";
type ScopeState = "DEFAULT" | "OVERRIDE" | "INHERITED";

interface MappingDraft {
  targetWorkflowInputId: string;
  sourceType: SourceType;
  sourceFormFieldId: string;
  sourceExpressionJson: unknown;
  constantJson: unknown;
  defaultJson: unknown;
  onMissing: string;
  transformJson: unknown;
  ordinal: number;
}

interface CatalogIntent {
  categoryId: string;
  categoryKey: string;
  name: string;
  description?: string | null;
  categoryGroup?: string | null;
}

interface FormOption {
  formId: string;
  formKey: string;
  name: string;
  description?: string | null;
  formVersionId: string;
  versionNo: number;
}

interface WorkflowOption {
  id: string;
  key: string;
  name: string;
  currentPublishedVersionId?: string | null;
  currentPublishedVersionNo?: number | null;
}

interface TenantOption {
  id: string;
  key: string;
  name: string;
}

interface ScopeBinding {
  state: ScopeState;
  tenantId?: string | null;
  tenantKey?: string | null;
  tenantName?: string | null;
  categoryVersionId: string;
  formVersionId: string;
  formName: string;
  formVersionNo: number;
  workflowVersionId: string;
  workflowName: string;
  workflowVersionNo: number;
  lockVersion: number;
  canEdit: boolean;
}

interface BindingOverview {
  categoryId: string;
  categoryKey: string;
  categoryName: string;
  sharedForm: {
    formId: string;
    formKey: string;
    name: string;
    formVersionId: string;
    versionNo: number;
  };
  scopes: ScopeBinding[];
}

interface CategoryIssue {
  code: string;
  severity: "ERROR" | "WARNING" | string;
  fieldPath?: string | null;
  message: string;
}

const newMapping = (ordinal: number): MappingDraft => ({
  targetWorkflowInputId: "",
  sourceType: "FORM_FIELD",
  sourceFormFieldId: "",
  sourceExpressionJson: null,
  constantJson: null,
  defaultJson: null,
  onMissing: "ERROR",
  transformJson: null,
  ordinal,
});

function friendlyError(error: unknown, fallback: string): string {
  const code =
    error && typeof error === "object" && "code" in error
      ? String(error.code)
      : "";
  const messages: Record<string, string> = {
    TENANT_MANAGEMENT_DENIED: "Bạn không có quyền cấu hình tenant này.",
    TENANT_ACCESS_DENIED: "Bạn không có quyền sử dụng tenant này.",
    CATEGORY_WORKFLOW_CONTRACT_MISMATCH:
      "Workflow này không tương thích với các trường dữ liệu của category.",
    CATEGORY_WORKFLOW_NOT_PUBLISHED: "Workflow được chọn chưa được Published.",
    CATEGORY_FORM_NOT_PUBLISHED: "Form dùng chung chưa được Published.",
    STALE_TENANT_BINDING:
      "Cấu hình vừa thay đổi. Hãy tải lại trang rồi thử lại.",
    CATEGORY_PUBLISH_VALIDATION_FAILED: "Cấu hình chưa hợp lệ để phát hành.",
  };
  return messages[code] ?? (error instanceof Error ? error.message : fallback);
}

export default function TicketCategoriesPage() {
  const [key, setKey] = useState("NEW_INTENT");
  const [name, setName] = useState("Nhu cầu nghiệp vụ mới");
  const [description, setDescription] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [versionId, setVersionId] = useState("");
  const [formVersionId, setFormVersionId] = useState("");
  const [workflowVersionId, setWorkflowVersionId] = useState("");
  const [revision, setRevision] = useState(0);
  const [mappings, setMappings] = useState<MappingDraft[]>([newMapping(0)]);
  const [catalog, setCatalog] = useState<CatalogIntent[]>([]);
  const [forms, setForms] = useState<FormOption[]>([]);
  const [workflows, setWorkflows] = useState<WorkflowOption[]>([]);
  const [tenants, setTenants] = useState<TenantOption[]>([]);
  const [overview, setOverview] = useState<BindingOverview | null>(null);
  const [selectedScope, setSelectedScope] = useState("DEFAULT");
  const [overrideWorkflowId, setOverrideWorkflowId] = useState("");
  const [editingOverride, setEditingOverride] = useState(false);
  const [message, setMessage] = useState("");
  const [validationIssues, setValidationIssues] = useState<CategoryIssue[]>([]);
  const [working, setWorking] = useState(false);
  const [loading, setLoading] = useState(true);

  const selectedBinding = useMemo(
    () =>
      overview?.scopes.find(
        (scope) => (scope.tenantId ?? "DEFAULT") === selectedScope,
      ) ?? null,
    [overview, selectedScope],
  );
  const selectedTenant =
    selectedScope === "DEFAULT"
      ? null
      : tenants.find((tenant) => tenant.id === selectedScope);
  const defaultBinding =
    overview?.scopes.find((scope) => scope.state === "DEFAULT") ?? null;

  async function loadPageData() {
    setLoading(true);
    try {
      const [catalogItems, formItems, workflowPage, tenantItems] =
        await Promise.all([
          apiGet<CatalogIntent[]>("/api/v1/ticket-categories?active=true"),
          apiGet<FormOption[]>("/api/v1/forms"),
          fetchWorkflows(),
          apiGet<TenantOption[]>("/api/v1/tenants"),
        ]);
      setCatalog(catalogItems);
      setForms(formItems);
      setWorkflows(workflowPage.items);
      setTenants(tenantItems);
    } catch (error) {
      setMessage(friendlyError(error, "Không thể tải dữ liệu cấu hình."));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void Promise.resolve().then(() => loadPageData());
  }, []);

  async function loadCategory(category: CatalogIntent) {
    setWorking(true);
    setMessage("");
    try {
      const [versionItems, bindingData] = await Promise.all([
        apiGet<
          Array<{
            id: string;
            status: string;
            revision: number;
            formVersionId: string;
            workflowVersionId: string;
          }>
        >(`/api/v1/ticket-categories/${category.categoryId}/versions`),
        apiGet<BindingOverview>(
          `/api/v1/ticket-categories/${category.categoryId}/bindings`,
        ),
      ]);
      const draft = versionItems.find((item) => item.status === "DRAFT");
      const mappingItems = draft
        ? await apiGet<MappingDraft[]>(
            `/api/v1/ticket-categories/${category.categoryId}/versions/${draft.id}/mappings`,
          )
        : [];
      setCategoryId(category.categoryId);
      setKey(category.categoryKey);
      setName(category.name);
      setDescription(category.description ?? "");
      setOverview(bindingData);
      setSelectedScope("DEFAULT");
      setEditingOverride(false);
      if (draft) {
        setVersionId(draft.id);
        setRevision(draft.revision);
        setFormVersionId(draft.formVersionId);
        setWorkflowVersionId(draft.workflowVersionId);
        setMappings(
          mappingItems.map((item, ordinal) => ({
            ...item,
            sourceFormFieldId: item.sourceFormFieldId ?? "",
            onMissing: item.onMissing ?? "ERROR",
            transformJson: item.transformJson ?? null,
            ordinal: item.ordinal ?? ordinal,
          })),
        );
      } else {
        setVersionId("");
        setRevision(0);
        setFormVersionId(bindingData.sharedForm.formVersionId);
        setWorkflowVersionId(bindingData.scopes[0]?.workflowVersionId ?? "");
        setMappings([newMapping(0)]);
      }
      setMessage(
        "Đã tải cấu hình category. Form dùng chung không thay đổi theo tenant.",
      );
    } catch (error) {
      setMessage(friendlyError(error, "Không thể tải category."));
    } finally {
      setWorking(false);
    }
  }

  async function refreshBindings() {
    if (!categoryId) return;
    const bindingData = await apiGet<BindingOverview>(
      `/api/v1/ticket-categories/${categoryId}/bindings`,
    );
    setOverview(bindingData);
  }

  async function createCategory() {
    setWorking(true);
    try {
      const category = await apiPost<{ id: string }>(
        "/api/v1/ticket-categories",
        { key, name, description, categoryGroup: "General", icon: null },
      );
      setCategoryId(category.id);
      setMessage(
        "Đã tạo category. Hãy chọn Form dùng chung và Workflow mặc định để tạo bản nháp.",
      );
      await loadPageData();
    } catch (error) {
      setMessage(friendlyError(error, "Tạo category thất bại."));
    } finally {
      setWorking(false);
    }
  }

  async function createDraft() {
    setWorking(true);
    try {
      const version = await apiPost<{ id: string; revision: number }>(
        `/api/v1/ticket-categories/${categoryId}/draft`,
        {
          formVersionId,
          workflowVersionId,
          creationPolicy: { initialStateKey: "SUBMITTED" },
        },
      );
      setVersionId(version.id);
      setRevision(version.revision);
      setMessage("Đã tạo bản nháp cấu hình mặc định.");
    } catch (error) {
      setMessage(friendlyError(error, "Tạo bản nháp thất bại."));
    } finally {
      setWorking(false);
    }
  }

  async function saveBinding() {
    if (!categoryId || !versionId) return;
    setWorking(true);
    try {
      const saved = await apiPut<{ revision?: number }>(
        `/api/v1/ticket-categories/${categoryId}/versions/${versionId}/binding`,
        {
          formVersionId,
          workflowVersionId,
          creationPolicy: { initialStateKey: "SUBMITTED" },
        },
        { headers: { "If-Match": String(revision) } },
      );
      setRevision(saved.revision ?? revision + 1);
      setMessage("Đã lưu Workflow mặc định. Form dùng chung vẫn giữ nguyên.");
    } catch (error) {
      setMessage(friendlyError(error, "Lưu cấu hình thất bại."));
    } finally {
      setWorking(false);
    }
  }

  async function saveMappings() {
    if (!categoryId || !versionId) return;
    setWorking(true);
    try {
      await apiPut(
        `/api/v1/ticket-categories/${categoryId}/versions/${versionId}/mappings`,
        mappings,
        { headers: { "If-Match": String(revision) } },
      );
      setRevision((value) => value + 1);
      setMessage("Đã lưu ánh xạ dữ liệu cho bản nháp.");
    } catch (error) {
      setMessage(friendlyError(error, "Lưu ánh xạ thất bại."));
    } finally {
      setWorking(false);
    }
  }

  async function lifecycle(action: "validate" | "publish") {
    if (!categoryId || !versionId) return;
    setWorking(true);
    try {
      const result = await apiPost<CategoryIssue[] | undefined>(
        `/api/v1/ticket-categories/${categoryId}/versions/${versionId}/${action}`,
        undefined,
        action === "publish"
          ? { headers: { "If-Match": String(revision) } }
          : undefined,
      );
      if (action === "validate") {
        setValidationIssues(Array.isArray(result) ? result : []);
      } else {
        setValidationIssues([]);
      }
      if (action === "publish") await refreshBindings();
      setMessage(
        action === "publish"
          ? "Đã Published cấu hình mặc định và cập nhật trạng thái tenant."
          : "Cấu hình hợp lệ theo kiểm tra hiện tại.",
      );
    } catch (error) {
      if (action === "publish") setValidationIssues([]);
      setMessage(
        friendlyError(
          error,
          action === "publish" ? "Publish thất bại." : "Kiểm tra thất bại.",
        ),
      );
    } finally {
      setWorking(false);
    }
  }

  function startOverride() {
    if (!selectedTenant || !defaultBinding) return;
    setOverrideWorkflowId(
      selectedBinding?.workflowVersionId ?? defaultBinding.workflowVersionId,
    );
    setEditingOverride(true);
    setMessage(`Đang tạo cấu hình riêng cho ${selectedTenant.name}.`);
  }

  async function saveOverride() {
    if (!categoryId || !selectedTenant || !overrideWorkflowId) return;
    setWorking(true);
    try {
      await apiPut(`/api/v1/ticket-categories/${categoryId}/bindings`, {
        tenantId: selectedTenant.id,
        workflowVersionId: overrideWorkflowId,
        expectedLockVersion:
          selectedBinding?.state === "OVERRIDE"
            ? selectedBinding.lockVersion
            : null,
      });
      await refreshBindings();
      setEditingOverride(false);
      setMessage(
        `Đã lưu Workflow riêng cho ${selectedTenant.name}. Form vẫn dùng bản dùng chung.`,
      );
    } catch (error) {
      setMessage(friendlyError(error, "Lưu override thất bại."));
    } finally {
      setWorking(false);
    }
  }

  async function deleteOverride() {
    if (!categoryId || !selectedTenant) return;
    setWorking(true);
    try {
      await apiDelete(
        `/api/v1/ticket-categories/${categoryId}/bindings/${selectedTenant.id}`,
      );
      await refreshBindings();
      setEditingOverride(false);
      setMessage(
        `Đã xóa override của ${selectedTenant.name}; tenant sẽ dùng Workflow mặc định.`,
      );
    } catch (error) {
      setMessage(friendlyError(error, "Không thể xóa override."));
    } finally {
      setWorking(false);
    }
  }

  function updateMapping(index: number, next: Partial<MappingDraft>) {
    setMappings((current) =>
      current.map((item, itemIndex) =>
        itemIndex === index ? { ...item, ...next } : item,
      ),
    );
  }

  const selectedWorkflow = workflows.find(
    (workflow) => workflow.currentPublishedVersionId === workflowVersionId,
  );
  const editWorkflow = selectedBinding?.state === "OVERRIDE" || editingOverride;
  const displayedWorkflowId = editWorkflow
    ? overrideWorkflowId || selectedBinding?.workflowVersionId || ""
    : workflowVersionId;

  return (
    <AuthRouteGuard roles={["WORKFLOW_OWNER", "WORKFLOW_EDITOR", "ADMIN"]}>
      <div className="space-y-7" data-testid="ticket-categories-page">
        <PageHeader
          eyebrow="Cấu hình nghiệp vụ"
          title="Nhu cầu nghiệp vụ"
          description="Một category dùng một Form chung. Chỉ Workflow được phép thay đổi theo tenant."
        />
        <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
          <section className="space-y-6 rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm sm:p-6">
            <div className="flex flex-col gap-3 border-b border-slate-100 pb-5 sm:flex-row sm:items-start sm:justify-between">
              <div>
                <h2 className="text-base font-semibold text-slate-950">
                  Cấu hình category
                </h2>
              </div>
              <StatusBadge value={versionId ? "DRAFT" : "READY"} />
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="text-sm font-semibold text-slate-700">
                Tên hiển thị
                <input
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  className="mt-1.5 w-full rounded-xl border border-slate-300 px-3 py-2.5 text-sm font-normal"
                />
              </label>
              <label className="text-sm font-semibold text-slate-700">
                categoryKey
                <input
                  value={key}
                  disabled={Boolean(categoryId)}
                  onChange={(event) =>
                    setKey(
                      event.target.value
                        .toUpperCase()
                        .replace(/[^A-Z0-9_]/g, "_"),
                    )
                  }
                  className="mt-1.5 w-full rounded-xl border border-slate-300 px-3 py-2.5 font-mono text-sm font-normal disabled:bg-slate-100"
                />
              </label>
              <label className="text-sm font-semibold text-slate-700 sm:col-span-2">
                Mô tả
                <textarea
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  className="mt-1.5 min-h-20 w-full rounded-xl border border-slate-300 px-3 py-2.5 text-sm font-normal"
                />
              </label>
            </div>
            <div className="rounded-xl border border-blue-100 bg-blue-50/60 p-4">
              <p className="text-xs font-bold tracking-wide text-blue-800 uppercase">
                Form dùng chung
              </p>
              <div className="mt-3 grid gap-3 sm:grid-cols-2">
                <label className="text-xs font-semibold text-blue-950">
                  Form
                  <select
                    value={formVersionId}
                    onChange={(event) => setFormVersionId(event.target.value)}
                    disabled={Boolean(overview?.sharedForm)}
                    className="mt-1.5 w-full rounded-lg border border-blue-200 bg-white px-3 py-2 text-sm font-normal disabled:bg-blue-50"
                  >
                    <option value="">Chọn Form đã Published</option>
                    {forms.map((form) => (
                      <option
                        key={form.formVersionId}
                        value={form.formVersionId}
                      >
                        {form.name} · v{form.versionNo}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
            </div>
            <div className="rounded-xl border border-slate-200 bg-slate-50/70 p-4">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <p className="text-xs font-bold tracking-wide text-slate-700 uppercase">
                    Phạm vi Workflow
                  </p>
                </div>
                <select
                  value={selectedScope}
                  onChange={(event) => {
                    setSelectedScope(event.target.value);
                    setEditingOverride(false);
                    setOverrideWorkflowId(
                      overview?.scopes.find(
                        (scope) =>
                          (scope.tenantId ?? "DEFAULT") === event.target.value,
                      )?.workflowVersionId ?? "",
                    );
                  }}
                  className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm"
                >
                  <option value="DEFAULT">DEFAULT · Mặc định</option>
                  {tenants.map((tenant) => (
                    <option key={tenant.id} value={tenant.id}>
                      {tenant.name} ·{" "}
                      {overview?.scopes.find(
                        (scope) => scope.tenantId === tenant.id,
                      )?.state ?? "INHERITED"}
                    </option>
                  ))}
                </select>
              </div>
              {selectedBinding ? (
                <div className="mt-4 rounded-xl border border-white bg-white p-4 shadow-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusBadge value={selectedBinding.state} />
                    {selectedBinding.state === "INHERITED" ? (
                      <span className="text-xs text-slate-500">
                        Đang dùng Workflow mặc định
                      </span>
                    ) : null}
                  </div>
                  <p className="mt-3 text-sm font-semibold text-slate-900">
                    {selectedBinding.workflowName} · v
                    {selectedBinding.workflowVersionNo}
                  </p>
                  <p className="mt-1 text-xs text-slate-500">
                    Form: {selectedBinding.formName} · v
                    {selectedBinding.formVersionNo}
                  </p>
                  {selectedTenant &&
                  selectedBinding.state === "INHERITED" &&
                  !editingOverride ? (
                    <button
                      type="button"
                      onClick={startOverride}
                      disabled={working || !defaultBinding}
                      className="mt-4 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2 text-xs font-semibold text-blue-700 disabled:opacity-50"
                    >
                      Tạo cấu hình riêng
                    </button>
                  ) : null}
                  {selectedTenant && editWorkflow ? (
                    <div className="mt-4 space-y-3 border-t border-slate-100 pt-4">
                      <label className="block text-xs font-semibold text-slate-700">
                        Workflow riêng cho tenant
                        <select
                          value={displayedWorkflowId}
                          onChange={(event) =>
                            setOverrideWorkflowId(event.target.value)
                          }
                          className="mt-1.5 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm"
                        >
                          <option value="">Chọn Workflow đã Published</option>
                          {workflows
                            .filter(
                              (workflow) => workflow.currentPublishedVersionId,
                            )
                            .map((workflow) => (
                              <option
                                key={workflow.currentPublishedVersionId}
                                value={workflow.currentPublishedVersionId ?? ""}
                              >
                                {workflow.name} · v
                                {workflow.currentPublishedVersionNo ?? "?"}
                              </option>
                            ))}
                        </select>
                      </label>
                      <div className="flex flex-wrap gap-2">
                        <button
                          type="button"
                          onClick={() => void saveOverride()}
                          disabled={
                            working ||
                            !overrideWorkflowId ||
                            !selectedBinding.canEdit
                          }
                          className="rounded-lg bg-blue-600 px-3 py-2 text-xs font-semibold text-white disabled:opacity-50"
                        >
                          Lưu override
                        </button>
                        {selectedBinding.state === "OVERRIDE" ? (
                          <button
                            type="button"
                            onClick={() => void deleteOverride()}
                            disabled={working || !selectedBinding.canEdit}
                            className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-semibold text-rose-700 disabled:opacity-50"
                          >
                            Xóa override
                          </button>
                        ) : null}
                        <button
                          type="button"
                          onClick={() => setEditingOverride(false)}
                          disabled={working}
                          className="rounded-lg border border-slate-300 px-3 py-2 text-xs font-semibold text-slate-600"
                        >
                          Hủy
                        </button>
                      </div>
                    </div>
                  ) : null}
                </div>
              ) : (
                <p className="mt-4 rounded-lg bg-white px-3 py-2.5 text-xs text-slate-500">
                  Cấu hình mặc định chưa được phát hành.
                </p>
              )}
            </div>
            {selectedScope === "DEFAULT" ? (
              <>
                <div className="rounded-xl border border-indigo-100 bg-indigo-50/60 p-4">
                  <label className="block text-xs font-semibold text-indigo-950">
                    Workflow mặc định
                    <select
                      value={workflowVersionId}
                      onChange={(event) =>
                        setWorkflowVersionId(event.target.value)
                      }
                      className="mt-1.5 w-full rounded-lg border border-indigo-200 bg-white px-3 py-2 text-sm font-normal"
                    >
                      <option value="">Chọn Workflow đã Published</option>
                      {workflows
                        .filter(
                          (workflow) => workflow.currentPublishedVersionId,
                        )
                        .map((workflow) => (
                          <option
                            key={workflow.currentPublishedVersionId}
                            value={workflow.currentPublishedVersionId ?? ""}
                          >
                            {workflow.name} · v
                            {workflow.currentPublishedVersionNo ?? "?"}
                          </option>
                        ))}
                    </select>
                  </label>
                  {selectedWorkflow ? (
                    <p className="mt-2 text-xs text-indigo-900/70">Workflow mặc định</p>
                  ) : null}
                </div>
                <div>
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <h3 className="text-sm font-semibold text-slate-900">
                        Ánh xạ dữ liệu
                      </h3>
                    </div>
                    <button
                      type="button"
                      onClick={() =>
                        setMappings((current) => [
                          ...current,
                          newMapping(current.length),
                        ])
                      }
                      className="rounded-lg border border-slate-300 px-3 py-2 text-xs font-semibold text-slate-700"
                    >
                      + Thêm
                    </button>
                  </div>
                  <div className="mt-4 space-y-3">
                    {mappings.map((mapping, index) => (
                      <div
                        key={`${mapping.ordinal}-${index}`}
                        className="grid gap-3 rounded-xl border border-slate-200 bg-slate-50/60 p-3 sm:grid-cols-[1fr_150px_1fr_auto]"
                      >
                        <label className="text-xs font-semibold text-slate-600">
                          Workflow input ID
                          <input
                            value={mapping.targetWorkflowInputId}
                            onChange={(event) =>
                              updateMapping(index, {
                                targetWorkflowInputId: event.target.value,
                              })
                            }
                            placeholder="Chỉ dùng trong phần nâng cao"
                            className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-2.5 py-2 font-mono text-xs font-normal"
                          />
                        </label>
                        <label className="text-xs font-semibold text-slate-600">
                          Nguồn
                          <select
                            value={mapping.sourceType}
                            onChange={(event) =>
                              updateMapping(index, {
                                sourceType: event.target.value as SourceType,
                              })
                            }
                            className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-2.5 py-2 text-xs font-normal"
                          >
                            {[
                              "FORM_FIELD",
                              "SYSTEM_CONTEXT",
                              "CONSTANT",
                              "EXPRESSION",
                              "DEFAULT",
                            ].map((type) => (
                              <option key={type}>{type}</option>
                            ))}
                          </select>
                        </label>
                        <label className="text-xs font-semibold text-slate-600">
                          Tham chiếu
                          <input
                            value={mapping.sourceFormFieldId}
                            onChange={(event) =>
                              updateMapping(index, {
                                sourceFormFieldId: event.target.value,
                              })
                            }
                            placeholder="ID trường / biểu thức"
                            className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-2.5 py-2 text-xs font-normal"
                          />
                        </label>
                        <label className="text-xs font-semibold text-slate-600 sm:col-span-2">
                          Transform (JSON, tùy chọn)
                          <input
                            value={
                              mapping.transformJson == null
                                ? ""
                                : typeof mapping.transformJson === "string"
                                  ? mapping.transformJson
                                  : JSON.stringify(mapping.transformJson)
                            }
                            onChange={(event) => {
                              const raw = event.target.value;
                              if (!raw.trim()) {
                                updateMapping(index, { transformJson: null });
                                return;
                              }
                              try {
                                updateMapping(index, { transformJson: JSON.parse(raw) });
                              } catch {
                                // Keep the raw text visible; the server returns a structured
                                // validation error when malformed JSON is submitted.
                                updateMapping(index, { transformJson: raw });
                              }
                            }}
                            placeholder='{"op":"TRIM"}'
                            className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-2.5 py-2 font-mono text-xs font-normal"
                          />
                        </label>
                        <button
                          type="button"
                          aria-label="Xóa ánh xạ"
                          onClick={() =>
                            setMappings((current) =>
                              current.filter(
                                (_, itemIndex) => itemIndex !== index,
                              ),
                            )
                          }
                          className="self-end rounded-lg px-2 py-2 text-slate-400 hover:bg-rose-50 hover:text-rose-600"
                        >
                          ×
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
                <div className="flex flex-wrap gap-2 border-t border-slate-100 pt-5">
                  <button
                    type="button"
                    onClick={() => void createCategory()}
                    disabled={working || Boolean(categoryId) || loading}
                    className="rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
                  >
                    Tạo category
                  </button>
                  <button
                    type="button"
                    onClick={() => void createDraft()}
                    disabled={
                      working ||
                      !categoryId ||
                      Boolean(versionId) ||
                      !formVersionId ||
                      !workflowVersionId
                    }
                    className="rounded-xl border border-slate-300 px-4 py-2.5 text-sm font-semibold text-slate-700 disabled:opacity-50"
                  >
                    Tạo bản nháp
                  </button>
                  <button
                    type="button"
                    onClick={() => void saveBinding()}
                    disabled={working || !versionId}
                    className="rounded-xl border border-blue-200 bg-blue-50 px-4 py-2.5 text-sm font-semibold text-blue-700 disabled:opacity-50"
                  >
                    Lưu mặc định
                  </button>
                  <button
                    type="button"
                    onClick={() => void saveMappings()}
                    disabled={working || !versionId}
                    className="rounded-xl border border-slate-300 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 disabled:opacity-50"
                  >
                    Lưu ánh xạ
                  </button>
                  <button
                    type="button"
                    onClick={() => void lifecycle("validate")}
                    disabled={working || !versionId}
                    className="rounded-xl border border-slate-300 px-4 py-2.5 text-sm font-semibold text-slate-700 disabled:opacity-50"
                  >
                    Kiểm tra
                  </button>
                  <button
                    type="button"
                    onClick={() => void lifecycle("publish")}
                    disabled={working || !versionId}
                    className="rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50"
                  >
                    Publish
                  </button>
                </div>
              </>
            ) : null}
            {message ? (
              <p
                role="status"
                className="rounded-xl bg-slate-100 px-3 py-2.5 text-sm text-slate-700"
              >
                {message}
              </p>
            ) : null}
            {validationIssues.length > 0 ? (
              <div
                role="region"
                aria-label="Kết quả kiểm tra category"
                className="space-y-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-3 text-sm"
              >
                <p className="font-semibold text-amber-950">
                  Kết quả kiểm tra ({validationIssues.length})
                </p>
                <ul className="space-y-1.5 text-xs text-amber-900">
                  {validationIssues.map((issue, index) => (
                    <li key={`${issue.code}-${issue.fieldPath ?? "root"}-${index}`}>
                      <span className="font-mono font-semibold">{issue.code}</span>
                      {issue.fieldPath ? ` · ${issue.fieldPath}` : ""}: {issue.message}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </section>
          <aside className="space-y-4">
            <div className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm">
              <h2 className="text-sm font-semibold text-slate-900">
                Category đã Published
              </h2>
              <div className="mt-4 space-y-2">
                {loading ? (
                  <p className="text-xs text-slate-400">Đang tải…</p>
                ) : catalog.length === 0 ? (
                  <p className="text-xs text-slate-400">
                    Chưa có category đã Published.
                  </p>
                ) : (
                  catalog.map((item) => (
                    <button
                      type="button"
                      key={item.categoryId}
                      onClick={() => void loadCategory(item)}
                      className={`w-full rounded-xl border p-3 text-left transition ${categoryId === item.categoryId ? "border-blue-300 bg-blue-50" : "border-slate-100 hover:border-blue-200 hover:bg-slate-50"}`}
                    >
                      <p className="text-sm font-semibold text-slate-800">
                        {item.name}
                      </p>
                      <p className="mt-1 text-xs text-slate-500">
                        {item.description || "Chưa có mô tả"}
                      </p>
                      <p className="mt-2 font-mono text-[10px] text-slate-400">
                        {item.categoryKey}
                      </p>
                    </button>
                  ))
                )}
              </div>
            </div>
            {overview ? (
              <div className="rounded-2xl border border-slate-200/80 bg-white p-5 text-xs text-slate-500 shadow-sm">
                <p className="font-semibold text-slate-700">Form dùng chung</p>
                <p className="mt-2 text-sm text-slate-900">
                  {overview.sharedForm.name} · v{overview.sharedForm.versionNo}
                </p>
              </div>
            ) : null}
          </aside>
        </div>
      </div>
    </AuthRouteGuard>
  );
}
