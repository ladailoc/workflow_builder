import Link from "next/link";
import type { ReactNode } from "react";
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
      {value}
    </span>
  );
}

export function AdminPageHeader({
  title,
  description,
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
        <p className="mt-1 text-sm text-slate-500">{description}</p>
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

export function LoadingPanel({ label = "Loading…" }: { label?: string }) {
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
  detail,
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
      <p className="mt-2 text-sm text-slate-500">{detail}</p>
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
        Name
        <input
          required
          value={form.name}
          onChange={(event) => onChange({ ...form, name: event.target.value })}
          className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 font-normal"
        />
      </label>
      <label className="block text-sm font-semibold">
        Stable key
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
        Category
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
        WorkflowDefinition
        <select
          required
          value={form.workflowDefinitionId}
          onChange={(event) =>
            onChange({ ...form, workflowDefinitionId: event.target.value })
          }
          className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 font-normal"
        >
          <option value="">Select a WorkflowDefinition</option>
          {workflows.map((workflow) => (
            <option key={workflow.id} value={workflow.id}>
              {workflow.name} · {workflow.lifecycle}
              {workflow.currentPublishedVersionNo
                ? ` · V${workflow.currentPublishedVersionNo}`
                : " · no Published version"}
            </option>
          ))}
        </select>
        <span className="mt-1 block text-xs font-normal text-slate-500">
          The mapping always targets WorkflowDefinition; Published version is
          resolved at form load and Submit.
        </span>
      </label>
      <label className="block text-sm font-semibold">
        Description
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
          Active in business catalog
        </label>
      )}
      <div className="flex justify-end">
        <button
          disabled={saving || !form.workflowDefinitionId}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
        >
          {saving ? "Saving…" : submitLabel}
        </button>
      </div>
    </form>
  );
}
