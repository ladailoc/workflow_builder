"use client";
import { use, useState } from "react";
import { apiPut } from "@/shared/api/client";
export default function WorkflowContractPage({
  params,
}: {
  params: Promise<{ workflowId: string; versionId: string }>;
}) {
  const { workflowId, versionId } = use(params);
  const [revision, setRevision] = useState(0);
  const [inputs, setInputs] = useState("[]");
  const [states, setStates] = useState("[]");
  const [message, setMessage] = useState("");
  async function save(path: string, value: string) {
    try {
      await apiPut(
        `/api/v1/workflows/${workflowId}/versions/${versionId}/${path}`,
        JSON.parse(value),
        { headers: { "If-Match": String(revision) } },
      );
      setRevision((v) => v + 1);
      setMessage(`${path} saved; workflow draft revision advanced.`);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Save failed");
    }
  }
  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">
          Workflow Inputs &amp; Business States
        </h1>
        <p className="text-sm text-slate-500">
          Typed inputs are independent from form field keys. Display states
          remain separate from technical node status.
        </p>
      </div>
      <label className="block text-sm">
        Draft revision
        <input
          type="number"
          className="ml-2 w-24 rounded border p-2"
          value={revision}
          onChange={(e) => setRevision(Number(e.target.value))}
        />
      </label>
      <div className="grid gap-4 lg:grid-cols-2">
        <section className="rounded-xl border bg-white p-5">
          <h2 className="font-semibold">Inputs</h2>
          <textarea
            className="mt-3 h-96 w-full rounded border p-3 font-mono text-xs"
            value={inputs}
            onChange={(e) => setInputs(e.target.value)}
          />
          <button
            className="mt-3 rounded bg-blue-600 px-4 py-2 text-sm font-semibold text-white"
            onClick={() => save("inputs", inputs)}
          >
            Save Inputs
          </button>
        </section>
        <section className="rounded-xl border bg-white p-5">
          <h2 className="font-semibold">Business states</h2>
          <textarea
            className="mt-3 h-96 w-full rounded border p-3 font-mono text-xs"
            value={states}
            onChange={(e) => setStates(e.target.value)}
          />
          <button
            className="mt-3 rounded bg-blue-600 px-4 py-2 text-sm font-semibold text-white"
            onClick={() => save("states", states)}
          >
            Save States
          </button>
        </section>
      </div>
      {message && <p className="text-sm text-slate-600">{message}</p>}
    </div>
  );
}
