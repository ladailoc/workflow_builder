import type { TimelineEntry } from "./types";

const TIMELINE_LABELS: Record<string, string> = {
  "NODE:COMPLETED": "Hoàn tất bước xử lý",
  "NODE:RUNNING": "Đang xử lý bước",
  "NODE:WAITING": "Bước đang chờ xử lý",
  "NODE:FAILED": "Bước xử lý thất bại",
  "NODE:CANCELLED": "Bước đã hủy",
  "TASK:READY": "Công việc sẵn sàng",
  "TASK:CLAIMED": "Đã nhận công việc",
  "TASK:IN_PROGRESS": "Đang xử lý công việc",
  "TASK:COMPLETED": "Hoàn tất công việc",
  "TASK:FAILED": "Công việc xử lý thất bại",
  "PARTICIPANT:RESOLVED": "Đã xác định người xử lý",
  "ROUTING:SINGLE_BY_PORT": "Chuyển sang nhánh xử lý",
  "INTEGRATION:COMPLETED": "Hoàn tất kết nối hệ thống",
  "INTEGRATION:FAILED": "Kết nối hệ thống thất bại",
  "SLA:RUNNING": "Đang theo dõi thời hạn SLA",
  "SLA:COMPLETED": "Đã hoàn tất theo dõi thời hạn SLA",
  "CHILD_EVENT:RUNNING": "Quy trình liên quan đang xử lý",
  "CHILD_EVENT:COMPLETED": "Hoàn tất quy trình liên quan",
};

const GENERIC_TIMELINE_LABELS: Record<string, string> = {
  NODE: "Cập nhật bước xử lý",
  TASK: "Cập nhật công việc",
  PARTICIPANT: "Cập nhật người xử lý",
  ROUTING: "Cập nhật luồng xử lý",
  INTEGRATION: "Cập nhật kết nối hệ thống",
  SLA: "Cập nhật thời hạn SLA",
  CHILD_EVENT: "Cập nhật quy trình liên quan",
  AUDIT: "Cập nhật hồ sơ",
};

function normalizeTimelinePart(value: string | null | undefined): string {
  return (value ?? "").trim().toUpperCase().replace(/\s+/g, "_");
}

export function formatTimelineEntry(entry: Pick<TimelineEntry, "type" | "state">): string {
  const type = normalizeTimelinePart(entry.type);
  const state = normalizeTimelinePart(entry.state);
  const specificLabel = TIMELINE_LABELS[`${type}:${state}`];

  if (specificLabel) {
    return specificLabel;
  }

  const baseType = type.split(":", 1)[0];
  return GENERIC_TIMELINE_LABELS[baseType] ?? "Cập nhật tiến trình";
}
