"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { AuthRouteGuard, useAuthSession } from "@/features/auth";
import {
  AdminPageHeader,
  LifecycleBadge,
  LoadingPanel,
  changeWorkflowLifecycle,
  cloneVersionAsDraft,
  createWorkflowDraft,
  fetchVersionDiff,
  fetchWorkflow,
  type WorkflowDetail,
} from "@/features/workflow-management";
import { ErrorState } from "@/shared/components/ui/error-state";

export default function WorkflowDetailPage() {
  const { workflowId } = useParams<{ workflowId: string }>();
  const router = useRouter();
  const { hasRole } = useAuthSession();
  const canManageLifecycle = hasRole(["WORKFLOW_OWNER", "ADMIN"]);
  const [detail, setDetail] = useState<WorkflowDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [diffFrom, setDiffFrom] = useState("");
  const [diffTo, setDiffTo] = useState("");
  const [diff, setDiff] = useState<Record<string, unknown> | null>(null);

  const load = () => {
    setError(null);
    fetchWorkflow(workflowId)
      .then((value) => {
        setDetail(value);
        if (value.versions.length > 1) {
          setDiffFrom(value.versions[1].id);
          setDiffTo(value.versions[0].id);
        }
      })
      .catch((reason: unknown) =>
        setError(
          reason instanceof Error ? reason.message : "Không tìm thấy quy trình",
        ),
      );
  };

  useEffect(() => {
    let ignore = false;
    fetchWorkflow(workflowId)
      .then((value) => {
        if (ignore) return;
        setDetail(value);
        if (value.versions.length > 1) {
          setDiffFrom(value.versions[1].id);
          setDiffTo(value.versions[0].id);
        }
      })
      .catch((reason: unknown) => {
        if (!ignore)
            setError(
            reason instanceof Error ? reason.message : "Không tìm thấy quy trình",
          );
      });
    return () => {
      ignore = true;
    };
  }, [workflowId]);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      load();
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : "Thao tác quy trình thất bại",
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthRouteGuard
      roles={["WORKFLOW_OWNER", "WORKFLOW_EDITOR", "OPERATOR", "ADMIN"]}
    >
      {!detail && !error ? (
        <LoadingPanel label="Đang tải thông tin quy trình…" />
      ) : error && !detail ? (
        <ErrorState
          title="Không thể tải quy trình"
          message={error}
          onRetry={load}
        />
      ) : detail ? (
        <div className="space-y-6" data-testid="workflow-detail-page">
          <AdminPageHeader
            title={detail.workflow.name}
            description={
              detail.workflow.description ||
              "Quản lý WorkflowDefinition và lịch sử phiên bản không thể thay đổi."
            }
            action={<LifecycleBadge value={detail.workflow.lifecycle} />}
          />
          {error && (
            <p
              role="alert"
              className="rounded-lg bg-rose-50 p-3 text-sm text-rose-700"
            >
              {error}
            </p>
          )}

          <section className="grid gap-4 md:grid-cols-4">
            <Info label="Khóa ổn định" value={detail.workflow.key} mono />
            <Info
              label="Đã phát hành"
              value={
                detail.workflow.currentPublishedVersionNo
                  ? `V${detail.workflow.currentPublishedVersionNo}`
                  : "Chưa có phiên bản đã phát hành"
              }
            />
            <Info
              label="Bản nháp hiện tại"
              value={
                detail.workflow.activeDraftVersionNo
                  ? `V${detail.workflow.activeDraftVersionNo}`
                  : "Chưa có bản nháp"
              }
            />
            <Info
              label="Số phiên bản"
              value={String(detail.workflow.versionCount)}
            />
          </section>

          {detail.workflow.lifecycle === "SUSPENDED" && (
            <Guidance
              title="Quy trình đang tạm dừng"
              text="Không thể tạo sự kiện mới. Các sự kiện đang chạy vẫn tiếp tục trên phiên bản đã gắn."
            />
          )}
          {detail.workflow.lifecycle === "ARCHIVED" && (
            <Guidance
              title="Quy trình đã lưu trữ"
              text="Không thể bắt đầu yêu cầu mới hoặc tạo bản nháp; lịch sử vẫn được giữ lại."
            />
          )}
          {!detail.workflow.currentPublishedVersionId && (
            <Guidance
              title="Chưa có phiên bản đã phát hành"
              text="Định nghĩa này có thể được cấu hình, nhưng chưa thể bắt đầu xử lý yêu cầu."
            />
          )}

          <div className="flex flex-wrap gap-2">
            {!detail.workflow.activeDraftVersionId &&
              detail.workflow.lifecycle !== "ARCHIVED" && (
                <button
                  disabled={busy}
                  onClick={() =>
                    run(async () => {
                      const draft = await createWorkflowDraft(
                        workflowId,
                        detail.workflow.currentPublishedVersionId ?? undefined,
                      );
                      router.push(
                        `/workflows/${workflowId}/versions/${draft.id}/builder`,
                      );
                    })
                  }
                  className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                >
                  Tạo bản nháp
                </button>
              )}
            {detail.workflow.activeDraftVersionId && (
              <Link
                href={`/workflows/${workflowId}/versions/${detail.workflow.activeDraftVersionId}/builder`}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white"
              >
                Mở bản nháp
              </Link>
            )}
            {canManageLifecycle && detail.workflow.lifecycle === "ACTIVE" && (
              <button
                disabled={busy}
                onClick={() => {
                  if (
                    window.confirm(
                      "Tạm dừng quy trình này? Các yêu cầu mới sẽ không thể bắt đầu.",
                    )
                  )
                    void run(() =>
                      changeWorkflowLifecycle(
                        workflowId,
                        "suspend",
                        detail.workflow.lockVersion,
                        "Suspended from Workflow Management",
                      ),
                    );
                }}
                className="rounded-lg border border-orange-300 px-4 py-2 text-sm font-semibold text-orange-700"
              >
                Tạm dừng
              </button>
            )}
            {canManageLifecycle &&
              detail.workflow.lifecycle === "SUSPENDED" && (
                <button
                  disabled={busy}
                  onClick={() =>
                    void run(() =>
                      changeWorkflowLifecycle(
                        workflowId,
                        "reactivate",
                        detail.workflow.lockVersion,
                        "Reactivated from Workflow Management",
                      ),
                    )
                  }
                  className="rounded-lg border border-emerald-300 px-4 py-2 text-sm font-semibold text-emerald-700"
                >
                  Kích hoạt lại
                </button>
              )}
            {canManageLifecycle && detail.workflow.lifecycle !== "ARCHIVED" && (
              <button
                disabled={busy || Boolean(detail.workflow.activeDraftVersionId)}
                title={
                  detail.workflow.activeDraftVersionId
                    ? "Hãy xóa bản nháp hiện tại trước khi lưu trữ"
                    : undefined
                }
                onClick={() => {
                  if (
                    window.confirm(
                      "Lưu trữ quy trình này? Quy trình sẽ không nhận yêu cầu mới.",
                    )
                  )
                    void run(() =>
                      changeWorkflowLifecycle(
                        workflowId,
                        "archive",
                        detail.workflow.lockVersion,
                        "Archived from Workflow Management",
                      ),
                    );
                }}
                className="rounded-lg border border-rose-300 px-4 py-2 text-sm font-semibold text-rose-700 disabled:opacity-40"
              >
                Lưu trữ
              </button>
            )}
          </div>

          <section className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
            <div className="border-b border-slate-200 px-5 py-4">
              <h2 className="font-semibold text-slate-900">Lịch sử phiên bản</h2>
              <p className="text-xs text-slate-500">
                Các phiên bản đã phát hành, bị thay thế hoặc lưu trữ không thể chỉnh sửa.
              </p>
            </div>
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 text-left text-sm">
                <thead className="bg-slate-50 text-xs text-slate-500 uppercase">
                  <tr>
                    <th className="px-5 py-3">Phiên bản</th>
                    <th className="px-5 py-3">Trạng thái</th>
                    <th className="px-5 py-3">Tạo / phát hành</th>
                    <th className="px-5 py-3">Thao tác</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {detail.versions.map((version) => (
                    <tr
                      key={version.id}
                      data-testid={`version-row-${version.id}`}
                    >
                      <td className="px-5 py-4 font-semibold">
                        V{version.versionNo}
                      </td>
                      <td className="px-5 py-4">
                        <LifecycleBadge value={version.status} />
                      </td>
                      <td className="px-5 py-4 text-xs text-slate-500">
                        {new Date(
                          version.publishedAt ?? version.createdAt,
                        ).toLocaleString("vi-VN")}
                      </td>
                      <td className="px-5 py-4">
                        <div className="flex flex-wrap gap-2">
                          <Link
                            href={`/workflows/${workflowId}/versions/${version.id}`}
                            className="text-sm font-semibold text-blue-700"
                          >
                            Xem
                          </Link>
                          <Link
                            href={`/workflows/${workflowId}/versions/${version.id}/builder`}
                            className="text-sm font-semibold text-blue-700"
                          >
                            {version.status === "DRAFT"
                              ? "Mở trình xây dựng"
                              : "Xem sơ đồ"}
                          </Link>
                          {canManageLifecycle &&
                            version.status !== "DRAFT" &&
                            !detail.workflow.activeDraftVersionId &&
                            detail.workflow.lifecycle !== "ARCHIVED" && (
                              <button
                                onClick={() =>
                                  void run(async () => {
                                    const clone = await cloneVersionAsDraft(
                                      workflowId,
                                      version.id,
                                      detail.workflow.lockVersion,
                                    );
                                    router.push(
                                      `/workflows/${workflowId}/versions/${clone.draftVersionId}/builder`,
                                    );
                                  })
                                }
                                className="text-sm font-semibold text-amber-700"
                              >
                                Sao chép thành bản nháp
                              </button>
                            )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          {detail.versions.length > 1 && (
            <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
              <h2 className="font-semibold text-slate-900">So sánh thay đổi</h2>
              <div className="mt-3 flex flex-wrap items-end gap-3">
                <VersionSelect
                  label="Từ"
                  value={diffFrom}
                  versions={detail.versions}
                  onChange={setDiffFrom}
                />
                <VersionSelect
                  label="Đến"
                  value={diffTo}
                  versions={detail.versions}
                  onChange={setDiffTo}
                />
                <button
                  disabled={!diffFrom || !diffTo || diffFrom === diffTo}
                  onClick={async () =>
                    setDiff(
                      await fetchVersionDiff(workflowId, diffFrom, diffTo),
                    )
                  }
                  className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold disabled:opacity-40"
                >
                  So sánh
                </button>
              </div>
              {diff && (
                <pre
                  data-testid="semantic-diff-result"
                  className="mt-4 max-h-80 overflow-auto rounded-lg bg-slate-950 p-4 text-xs text-slate-100"
                >
                  {JSON.stringify(diff, null, 2)}
                </pre>
              )}
            </section>
          )}
        </div>
      ) : null}
    </AuthRouteGuard>
  );
}

function Info({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <p className="text-xs font-semibold tracking-wide text-slate-400 uppercase">
        {label}
      </p>
      <p
        className={`mt-2 text-sm font-semibold text-slate-800 ${mono ? "font-mono" : ""}`}
      >
        {value}
      </p>
    </div>
  );
}
function Guidance({ title, text }: { title: string; text: string }) {
  return (
    <div className="rounded-xl border border-amber-200 bg-amber-50 p-4">
      <p className="font-semibold text-amber-900">{title}</p>
      <p className="mt-1 text-sm text-amber-800">{text}</p>
    </div>
  );
}
function VersionSelect({
  label,
  value,
  versions,
  onChange,
}: {
  label: string;
  value: string;
  versions: WorkflowDetail["versions"];
  onChange: (value: string) => void;
}) {
  return (
    <label className="text-xs font-semibold text-slate-600">
      {label}
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="mt-1 block rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm"
      >
        {versions.map((version) => (
          <option key={version.id} value={version.id}>
            V{version.versionNo} · {version.status}
          </option>
        ))}
      </select>
    </label>
  );
}
