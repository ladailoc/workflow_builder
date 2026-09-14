"use client";
import { useState } from "react";
import { apiPost, apiPut } from "@/shared/api/client";

const emptySchema = {
  formKey: "new_form",
  formType: "TICKET_FORM",
  fields: [],
};
export default function FormsPage() {
  const [key, setKey] = useState("new_form");
  const [name, setName] = useState("New Form");
  const [schema, setSchema] = useState(JSON.stringify(emptySchema, null, 2));
  const [result, setResult] = useState<Record<string, unknown> | null>(null);
  const [message, setMessage] = useState("");
  async function create() {
    try {
      const parsed = JSON.parse(schema);
      parsed.formKey = key;
      const created = await apiPost<Record<string, unknown>>("/api/v1/forms", {
        key,
        name,
        description: "",
        schema: parsed,
      });
      setResult(created);
      setMessage(
        "Draft created. Edit fields, validate, then publish when ready.",
      );
    } catch (error) {
      setMessage(
        error instanceof Error ? error.message : "Could not create form",
      );
    }
  }
  async function save() {
    try {
      const form = result?.form as { id?: string } | undefined;
      const draft = result?.draft as
        { id?: string; revision?: number } | undefined;
      if (!form?.id || !draft?.id) throw new Error("Create a draft first");
      await apiPut(
        `/api/v1/forms/${form.id}/versions/${draft.id}`,
        JSON.parse(schema),
        { headers: { "If-Match": String(draft.revision ?? 0) } },
      );
      setMessage(
        "Draft saved. Use the API validate/publish actions after review.",
      );
    } catch (error) {
      setMessage(
        error instanceof Error ? error.message : "Could not save form",
      );
    }
  }
  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">Form Builder</h1>
        <p className="text-sm text-slate-500">
          Reusable, versioned data collection. Publishing never changes existing
          category bindings.
        </p>
      </div>
      <div className="grid gap-3 rounded-xl border bg-white p-5 md:grid-cols-2">
        <label className="text-sm">
          Stable key
          <input
            className="mt-1 w-full rounded border p-2"
            value={key}
            onChange={(e) => setKey(e.target.value)}
          />
        </label>
        <label className="text-sm">
          Name
          <input
            className="mt-1 w-full rounded border p-2"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label className="text-sm md:col-span-2">
          FormVersion schema
          <textarea
            className="mt-1 h-96 w-full rounded border p-3 font-mono text-xs"
            value={schema}
            onChange={(e) => setSchema(e.target.value)}
          />
        </label>
        <div className="flex gap-2">
          <button
            className="rounded bg-blue-600 px-4 py-2 text-sm font-semibold text-white"
            onClick={create}
          >
            Create Form + Draft
          </button>
          <button
            className="rounded border px-4 py-2 text-sm font-semibold"
            onClick={save}
          >
            Save Draft
          </button>
        </div>
        {message && (
          <p className="text-sm text-slate-600 md:col-span-2">{message}</p>
        )}
      </div>
    </div>
  );
}
