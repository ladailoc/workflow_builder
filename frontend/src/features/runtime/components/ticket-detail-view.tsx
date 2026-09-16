"use client";

import Link from "next/link";
import type { TicketAggregate } from "../types";
import { getTicketDisplayName } from "../ticket-display";
import { StatusBadge } from "@/shared/components/ui/status-badge";

interface TicketDetailViewProps {
  aggregate: TicketAggregate;
}

const TECHNICAL_KEYS = new Set([
  "id",
  "uuid",
  "revision",
  "datarevision",
  "checksum",
  "schema",
  "lockversion",
  "workflowversion",
  "workflowversionid",
  "eventid",
  "nodeid",
  "nodeexecutionid",
  "executionid",
  "sourcefield",
  "subjectref",
  "subjectrefid",
  "creatorid",
  "requesttypeid",
]);

const FIELD_LABELS: Record<string, string> = {
  title: "Tiêu đề",
  description: "Mô tả",
  reason: "Lý do",
  urgencyreason: "Lý do yêu cầu gấp",
  comment: "Ghi chú",
  department: "Phòng ban",
  departmentid: "Phòng ban",
  equipmenttype: "Loại thiết bị",
  budget: "Ngân sách",
  justification: "Lý do yêu cầu",
  businessjustification: "Lý do yêu cầu",
  isurgent: "Yêu cầu này có gấp không?",
  isthisrequesturgent: "Yêu cầu này có gấp không?",
  estimatedcost: "Chi phí dự kiến",
  apikeysecret: "Khóa truy cập API",
  apiaccesskey: "Khóa truy cập API",
  email: "Email",
  phone: "Số điện thoại",
  address: "Địa chỉ",
};

function normalizedKey(key: string): string {
  return key.toLowerCase().replace(/[^a-z0-9]/g, "");
}

function isTechnicalField(key: string): boolean {
  const normalized = normalizedKey(key);
  return (
    TECHNICAL_KEYS.has(normalized) ||
    normalized.startsWith("workflowversion") ||
    normalized.startsWith("nodeexecution") ||
    normalized.startsWith("eventexecution")
  );
}

function isAttachmentField(key: string, value: unknown): boolean {
  const normalized = normalizedKey(key);
  return (
    normalized.includes("file") ||
    normalized.includes("attachment") ||
    (typeof value === "string" && value.startsWith("file://"))
  );
}

function displayFieldName(key: string): string {
  const knownLabel = FIELD_LABELS[normalizedKey(key)];
  if (knownLabel) return knownLabel;
  return key
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .replace(/^./, (character) => character.toUpperCase());
}

function displayValue(value: unknown): string {
  if (value === undefined || value === null || value === "") {
    return "Chưa cung cấp";
  }
  if (typeof value === "boolean") return value ? "Có" : "Không";
  if (Array.isArray(value)) {
    return value.map((item) => displayValue(item)).join(", ");
  }
  if (typeof value === "object") {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, item]) => `${displayFieldName(key)}: ${displayValue(item)}`)
      .join("; ");
  }
  return String(value);
}

function formatDate(value: string): string {
  return new Date(value).toLocaleString("vi-VN");
}

export function TicketDetailView({ aggregate }: TicketDetailViewProps) {
  const { ticket, currentEventId } = aggregate;
  const entries = Object.entries(ticket.dataJson || {});
  const attachments = entries.filter(([key, value]) =>
    isAttachmentField(key, value),
  );
  const displayEntries = entries.filter(
    ([key, value]) =>
      !isTechnicalField(key) && !isAttachmentField(key, value),
  );

  return (
    <div className="max-w-5xl space-y-6" data-testid="ticket-detail-view">
      <section className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="space-y-2">
            <p className="text-xs font-semibold tracking-wide text-blue-600 uppercase">
              Chi tiết ticket
            </p>
            <div className="flex flex-wrap items-center gap-2.5">
              <h1 className="text-xl font-bold tracking-tight text-slate-900">
                {getTicketDisplayName(ticket)}
              </h1>
              <span data-testid="ticket-status-badge">
                <StatusBadge value={ticket.status} />
                <span className="sr-only">{ticket.status}</span>
              </span>
            </div>
            <p className="text-xs text-slate-400">
              Ticket #{ticket.id.slice(0, 8)}
            </p>
          </div>

          {currentEventId && (
            <Link
              href={`/events/${currentEventId}`}
              data-testid="link-to-event"
              className="inline-flex items-center gap-2 rounded-lg bg-blue-50 px-3.5 py-2 text-xs font-semibold text-blue-700 transition-colors hover:bg-blue-100"
            >
              <span>Xem tiến trình xử lý</span>
              <svg
                className="h-3.5 w-3.5"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M14 5l7 7m0 0l-7 7m7-7H3"
                />
              </svg>
            </Link>
          )}
        </div>

        <dl className="mt-6 grid gap-3 border-t border-slate-100 pt-5 sm:grid-cols-3">
          <div>
            <dt className="text-xs font-medium text-slate-500">Trạng thái</dt>
            <dd className="mt-1">
              <StatusBadge value={ticket.status} />
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-slate-500">Ngày tạo</dt>
            <dd className="mt-1 text-sm font-semibold text-slate-800">
              {formatDate(ticket.createdAt)}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-slate-500">
              Cập nhật gần nhất
            </dt>
            <dd className="mt-1 text-sm font-semibold text-slate-800">
              {formatDate(ticket.updatedAt)}
            </dd>
          </div>
        </dl>
      </section>

      <section
        data-testid="business-data-section"
        className="space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-xs"
      >
        <div>
          <h2 className="text-base font-semibold text-slate-900">
            Thông tin yêu cầu
          </h2>
        </div>

        {displayEntries.length === 0 ? (
          <p className="rounded-lg bg-slate-50 p-4 text-sm text-slate-500">
            Chưa có thông tin yêu cầu.
          </p>
        ) : (
          <dl className="grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-2">
            {displayEntries.map(([key, value]) => (
              <div
                key={key}
                data-testid={`data-field-${key}`}
                className="border-b border-slate-100 pb-3"
              >
                <dt className="text-xs font-medium text-slate-500">
                  {displayFieldName(key)}
                </dt>
                <dd className="mt-1 break-words text-sm font-semibold text-slate-800">
                  {displayValue(value)}
                </dd>
              </div>
            ))}
          </dl>
        )}

        {attachments.length > 0 && (
          <div
            data-testid="attachments-section"
            className="border-t border-slate-100 pt-4"
          >
            <h3 className="text-sm font-semibold text-slate-900">
              Tệp đính kèm
            </h3>
            <ul className="mt-3 space-y-2">
              {attachments.map(([key]) => (
                <li
                  key={key}
                  data-testid={`attachment-item-${key}`}
                  className="flex items-center gap-2 rounded-lg bg-slate-50 px-3 py-2 text-sm text-slate-700"
                >
                  <svg
                    className="h-4 w-4 text-blue-500"
                    fill="none"
                    viewBox="0 0 24 24"
                    stroke="currentColor"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13"
                    />
                  </svg>
                  <span>{displayFieldName(key)} đã được đính kèm</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>
    </div>
  );
}
