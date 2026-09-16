const STATUS_STYLES: Record<string, string> = {
  DRAFT: "bg-amber-50 text-amber-700 ring-amber-600/20",
  SUBMITTED: "bg-blue-50 text-blue-700 ring-blue-600/20",
  IN_PROGRESS: "bg-indigo-50 text-indigo-700 ring-indigo-600/20",
  RUNNING: "bg-indigo-50 text-indigo-700 ring-indigo-600/20",
  WAITING: "bg-amber-50 text-amber-700 ring-amber-600/20",
  COMPLETED: "bg-emerald-50 text-emerald-700 ring-emerald-600/20",
  PUBLISHED: "bg-emerald-50 text-emerald-700 ring-emerald-600/20",
  READY: "bg-sky-50 text-sky-700 ring-sky-600/20",
  CLAIMED: "bg-violet-50 text-violet-700 ring-violet-600/20",
  FAILED: "bg-rose-50 text-rose-700 ring-rose-600/20",
  REJECTED: "bg-rose-50 text-rose-700 ring-rose-600/20",
  CANCELLED: "bg-slate-100 text-slate-600 ring-slate-500/20",
  ARCHIVED: "bg-slate-100 text-slate-600 ring-slate-500/20",
  SUPERSEDED: "bg-slate-100 text-slate-600 ring-slate-500/20",
  ACTIVE: "bg-emerald-50 text-emerald-700 ring-emerald-600/20",
  SUSPENDED: "bg-orange-50 text-orange-700 ring-orange-600/20",
  INACTIVE: "bg-slate-100 text-slate-600 ring-slate-500/20",
};

const STATUS_LABELS: Record<string, string> = {
  DRAFT: "DRAFT",
  SUBMITTED: "SUBMITTED",
  IN_PROGRESS: "IN_PROGRESS",
  RUNNING: "RUNNING",
  WAITING: "WAITING",
  COMPLETED: "COMPLETED",
  PUBLISHED: "PUBLISHED",
  READY: "READY",
  CLAIMED: "CLAIMED",
  FAILED: "FAILED",
  REJECTED: "REJECTED",
  CANCELLED: "CANCELLED",
  ARCHIVED: "ARCHIVED",
  SUPERSEDED: "SUPERSEDED",
  ACTIVE: "ACTIVE",
  SUSPENDED: "SUSPENDED",
  INACTIVE: "INACTIVE",
};

const OUTCOME_LABELS: Record<string, string> = {
  SUCCESS: "Thành công",
  ERROR: "Lỗi",
  FAILED: "Thất bại",
  APPROVED: "Đã phê duyệt",
  REJECTED: "Bị từ chối",
  RETURNED: "Đã trả lại",
  REVISION_REQUESTED: "Yêu cầu bổ sung",
  PENDING_APPROVAL: "Đang chờ phê duyệt",
  DEFAULT: "Mặc định",
};

export function StatusBadge({ value }: Readonly<{ value?: string | null }>) {
  const normalized = value?.toUpperCase() ?? "UNKNOWN";
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-semibold ring-1 ring-inset ${STATUS_STYLES[normalized] ?? "bg-slate-100 text-slate-600 ring-slate-500/20"}`}
    >
      <span className="h-1.5 w-1.5 rounded-full bg-current opacity-70" />
      {STATUS_LABELS[normalized] ?? normalized.replaceAll("_", " ")}
    </span>
  );
}

export function formatStatus(value?: string | null): string {
  const normalized = value?.toUpperCase() ?? "UNKNOWN";
  return STATUS_LABELS[normalized] ?? normalized.replaceAll("_", " ");
}

export function formatOutcome(value?: string | null): string {
  const normalized = value?.toUpperCase() ?? "UNKNOWN";
  return OUTCOME_LABELS[normalized] ?? normalized.replaceAll("_", " ");
}
