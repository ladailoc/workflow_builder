"use client";
import { useState } from "react";
import { apiPost, apiPut } from "@/shared/api/client";
export default function TicketCategoriesPage() {
  const [key, setKey] = useState("NEW_INTENT");
  const [name, setName] = useState("New business intent");
  const [categoryId, setCategoryId] = useState("");
  const [versionId, setVersionId] = useState("");
  const [formVersionId, setFormVersionId] = useState("");
  const [workflowVersionId, setWorkflowVersionId] = useState("");
  const [revision, setRevision] = useState(0);
  const [mappings, setMappings] = useState("[]");
  const [message, setMessage] = useState("");
  async function createCategory() {
    try {
      const category = await apiPost<{ id: string }>(
        "/api/v1/ticket-categories",
        { key, name, description: "", categoryGroup: "General", icon: null },
      );
      setCategoryId(category.id);
      setMessage(
        "Category created. Pin exact published Form and Workflow versions next.",
      );
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Create failed");
    }
  }
  async function createDraft() {
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
      setMessage("Binding draft created.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Draft failed");
    }
  }
  async function saveMappings() {
    try {
      await apiPut(
        `/api/v1/ticket-categories/${categoryId}/versions/${versionId}/mappings`,
        JSON.parse(mappings),
        { headers: { "If-Match": String(revision) } },
      );
      setRevision((value) => value + 1);
      setMessage(
        "Explicit mapping saved. Validate and publish via the category actions after resolving all required inputs.",
      );
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Mapping failed");
    }
  }
  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">
          Business Intent Binding
        </h1>
        <p className="text-sm text-slate-500">
          A categoryKey pins one FormVersion, one primary WorkflowVersion, and
          one explicit mapping.
        </p>
      </div>
      <div className="grid gap-3 rounded-xl border bg-white p-5 md:grid-cols-2">
        <label className="text-sm">
          categoryKey
          <input
            className="mt-1 w-full rounded border p-2"
            value={key}
            onChange={(e) => setKey(e.target.value)}
          />
        </label>
        <label className="text-sm">
          User-facing name
          <input
            className="mt-1 w-full rounded border p-2"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <button
          className="w-fit rounded bg-blue-600 px-4 py-2 text-sm font-semibold text-white"
          onClick={createCategory}
        >
          Create Category
        </button>
        <span />
        <label className="text-sm">
          Published FormVersion ID
          <input
            className="mt-1 w-full rounded border p-2"
            value={formVersionId}
            onChange={(e) => setFormVersionId(e.target.value)}
          />
        </label>
        <label className="text-sm">
          Published WorkflowVersion ID
          <input
            className="mt-1 w-full rounded border p-2"
            value={workflowVersionId}
            onChange={(e) => setWorkflowVersionId(e.target.value)}
          />
        </label>
        <button
          className="w-fit rounded border px-4 py-2 text-sm font-semibold"
          onClick={createDraft}
          disabled={!categoryId}
        >
          Create Binding Draft
        </button>
        <span />
        <label className="text-sm md:col-span-2">
          Field/System/Constant/Expression/Default mappings
          <textarea
            className="mt-1 h-72 w-full rounded border p-3 font-mono text-xs"
            value={mappings}
            onChange={(e) => setMappings(e.target.value)}
          />
        </label>
        <button
          className="w-fit rounded bg-slate-900 px-4 py-2 text-sm font-semibold text-white"
          onClick={saveMappings}
          disabled={!versionId}
        >
          Save Explicit Mapping
        </button>
        {message && (
          <p className="text-sm text-slate-600 md:col-span-2">{message}</p>
        )}
      </div>
    </div>
  );
}
