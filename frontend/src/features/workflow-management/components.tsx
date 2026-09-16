import Link from "next/link";
import type { ReactNode } from "react";
import { formatStatus } from "@/shared/components/ui/status-badge";
import type { WorkflowLifecycle, WorkflowSummary } from "./types";

export function LifecycleBadge({
  value,
}: {
  value: WorkflowLifecycle | string;
}) {
  const colors =
    value === "ACTIVE" || value === "PUBLISHED"
      ? "border-emerald-200 bg-emerald-50 text-emerald-700"
      : value === "DRAFT"
        ? "border-amber-200 bg-amber-50 text-amber-700"
        : value === "SUSPENDED"
          ? "border-orange-200 bg-orange-50 text-orange-700"
          : value === "ARCHIVED"
            ? "border-rose-200 bg-rose-50 text-rose-700"
            : "border-slate-200 bg-slate-50 text-slate-600";
  return (
    <span
      className={`rounded-full border px-2.5 py-1 text-xs font-semibold ${colors}`}
    >
      {formatStatus(value)}
    </span>
  );
}

export function AdminPageHeader({
  title,
  action,
}: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">
          {title}
        </h1>
      </div>
      {action}
    </div>
  );
}

export function PrimaryLink({
  href,
  children,
}: {
  href: string;
  children: ReactNode;
}) {
  return (
    <Link
      href={href}
      className="inline-flex items-center justify-center rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-700"
    >
      {children}
    </Link>
  );
}

export function LoadingPanel({ label = "Đang tải…" }: { label?: string }) {
  return (
    <div
      data-testid="management-loading"
      className="rounded-xl border border-slate-200 bg-white p-12 text-center text-sm text-slate-500"
    >
      {label}
    </div>
  );
}

export function EmptyPanel({
  title,
}: {
  title: string;
  detail: string;
}) {
  return (
    <div
      data-testid="management-empty"
      className="rounded-xl border border-dashed border-slate-300 bg-white p-12 text-center"
    >
      <h2 className="font-semibold text-slate-800">{title}</h2>
    </div>
  );
}

export interface RequestTypeFormValue {
  key: string;
  name: string;
  description: string;
  category: string;
  workflowDefinitionId: string;
  active: boolean;
}

export function RequestTypeForm({
  form,
  workflows,
  saving,
  error,
  submitLabel,
  editMode = false,
  onChange,
  onSubmit,
}: {
  form: RequestTypeFormValue;
  workflows: WorkflowSummary[];
  saving: boolean;
  error: string | null;
  submitLabel: string;
  editMode?: boolean;
  onChange: (form: RequestTypeFormValue) => void;
  onSubmit: () => Promise<void>;
}) {
  return (
    <form
      className="space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-sm"
      onSubmit={(event) => {
        event.preventDefault();
        void onSubmit();
      }}
    >
      {error && (
        <p
          role="alert"
          className="rounded-lg bg-rose-50 p-3 text-sm text-rose-700"
        >
          {error}
        </p>
      )}
      <label className="block text-sm font-semibold">
        Tên
        <input
          required
          value={form.name}
          onChange={(event) => onChange({ ...form, name: event.target.value })}
          className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 font-normal"
        />
      </label>
      <label className="block text-sm font-semibold">
        Khóa ổn định
        <input
          required
          disabled={editMode}
          value={form.key}
          onChange={(event) =>
            onChange({
              ...form,
              key: event.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, "_"),
            })
          }
          className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 font-mono font-normal disabled:bg-slate-100"
        />
      </label>
      <label className="block text-sm font-semibold">
        Danh mục
        <input
          required
          value={form.category}
          onChange={(event) =>
            onChange({ ...form, category: event.target.value })
          }
          className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 font-normal"
        />
      </label>
      <label className="block text-sm font-semibold">
        WorkflowDefinition (định danh quy trình)
        <select
          required
          value={form.workflowDefinitionId}
          onChange={(event) =>
            onChange({ ...form, workflowDefinitionId: event.target.value })
          }
          className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 font-normal"
        >
          <option value="">Chọn WorkflowDefinition</option>
          {workflows.map((workflow) => (
            <option key={workflow.id} value={workflow.id}>
              {workflow.name} · {formatStatus(workflow.lifecycle)}
              {workflow.currentPublishedVersionNo
                ? ` · V${workflow.currentPublishedVersionNo}`
                : " · chưa có phiên bản đã phát hành"}
            </option>
          ))}
        </select>
      </label>
      <label className="block text-sm font-semibold">
        Mô tả
        <textarea
          value={form.description}
          onChange={(event) =>
            onChange({ ...form, description: event.target.value })
          }
          className="mt-1 min-h-24 w-full rounded-lg border border-slate-300 px-3 py-2 font-normal"
        />
      </label>
      {!editMode && (
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={form.active}
            onChange={(event) =>
              onChange({ ...form, active: event.target.checked })
            }
          />{" "}
          Hiển thị trong danh mục yêu cầu
        </label>
      )}
      <div className="flex justify-end">
        <button
          disabled={saving || !form.workflowDefinitionId}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
        >
          {saving ? "Đang lưu…" : submitLabel}
        </button>
      </div>
    </form>
  );
}
