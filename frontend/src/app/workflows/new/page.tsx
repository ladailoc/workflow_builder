"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { AuthRouteGuard, useAuthSession } from "@/features/auth";
import {
  AdminPageHeader,
  createWorkflow,
} from "@/features/workflow-management";

export default function NewWorkflowPage() {
  const router = useRouter();
  const { actor } = useAuthSession();
  const [name, setName] = useState("");
  const [key, setKey] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  return (
    <AuthRouteGuard roles={["WORKFLOW_OWNER", "ADMIN"]}>
      <div
        className="mx-auto max-w-2xl space-y-6"
        data-testid="create-workflow-page"
      >
        <AdminPageHeader
          title="Tạo quy trình"
          description="Tạo định danh WorkflowDefinition ổn định. Ticket và sự kiện chạy thực tế không được tạo ở đây."
        />
        <form
          className="space-y-5 rounded-xl border border-slate-200 bg-white p-6 shadow-sm"
          onSubmit={async (event) => {
            event.preventDefault();
            if (!actor) return;
            setSaving(true);
            setError(null);
            try {
              const created = await createWorkflow({
                key,
                name,
                description,
                ownerId: actor.actorId,
              });
              router.push(`/workflows/${created.id}`);
            } catch (reason) {
              setError(
                reason instanceof Error
                  ? reason.message
                  : "Không thể tạo quy trình",
              );
            } finally {
              setSaving(false);
            }
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
          <label className="block text-sm font-semibold text-slate-700">
            Tên
            <input
              required
              value={name}
              onChange={(event) => setName(event.target.value)}
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 font-normal"
              placeholder="Phê duyệt mua sắm"
            />
          </label>
          <label className="block text-sm font-semibold text-slate-700">
            Khóa ổn định
            <input
              required
              value={key}
              onChange={(event) =>
                setKey(
                  event.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, "_"),
                )
              }
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 font-mono font-normal"
              placeholder="PURCHASE_APPROVAL"
            />
          </label>
          <label className="block text-sm font-semibold text-slate-700">
            Mô tả
            <textarea
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              className="mt-1 min-h-28 w-full rounded-lg border border-slate-300 px-3 py-2 font-normal"
            />
          </label>
          <div className="flex justify-end gap-3">
            <button
              type="button"
              onClick={() => router.back()}
              className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold"
            >
              Hủy
            </button>
            <button
              disabled={saving}
              className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
            >
              {saving ? "Đang tạo…" : "Tạo quy trình"}
            </button>
          </div>
        </form>
      </div>
    </AuthRouteGuard>
  );
}
