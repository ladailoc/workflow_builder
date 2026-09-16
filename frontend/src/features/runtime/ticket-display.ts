import type { TicketView } from "./types";

const TITLE_KEYS = [
  "title",
  "subject",
  "requestTitle",
  "requestName",
  "summary",
  "serviceName",
  "categoryName",
];

const SUMMARY_KEYS = [
  "description",
  "reason",
  "justification",
  "businessJustification",
  "comment",
  "notes",
];

const TYPE_RULES: Array<{ keys: string[]; label: string }> = [
  {
    keys: ["equipmentType", "device", "laptop", "computer", "hardware"],
    label: "Yêu cầu cấp thiết bị",
  },
  {
    keys: ["accessLevel", "systemAccess", "permission", "role"],
    label: "Yêu cầu cấp quyền truy cập",
  },
  {
    keys: ["leaveType", "leaveFrom", "leaveTo", "leaveStart", "leaveEnd"],
    label: "Yêu cầu nghỉ phép",
  },
  {
    keys: ["vendor", "vendorName", "purchaseOrder", "estimatedCost", "budget"],
    label: "Yêu cầu mua sắm",
  },
  {
    keys: ["employee", "employeeId", "evaluation", "performance", "rating"],
    label: "Yêu cầu đánh giá nhân sự",
  },
];

function normalizeKey(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]/g, "");
}

function isReadableText(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const text = value.trim();
  if (!text || text.length > 120) return false;
  if (/^(?:https?|file):\/\//i.test(text)) return false;
  if (/^[0-9a-f]{8}-[0-9a-f-]{27,}$/i.test(text)) return false;
  if (/^[A-Z][A-Za-z0-9]+Action$/.test(text)) return false;
  if (/^[A-Z0-9_]+$/.test(text) && text.includes("_")) return false;
  return true;
}

function valueByKey(
  data: Record<string, unknown>,
  keys: string[],
): string | undefined {
  const normalizedKeys = new Set(keys.map(normalizeKey));
  const entry = Object.entries(data).find(
    ([key, value]) => normalizedKeys.has(normalizeKey(key)) && isReadableText(value),
  );
  if (!entry || !isReadableText(entry[1])) return undefined;
  return entry[1].trim();
}

export function getTicketDisplayName(ticket: Pick<TicketView, "dataJson">): string {
  const data = ticket.dataJson ?? {};
  const explicitTitle = valueByKey(data, TITLE_KEYS);
  if (explicitTitle) return explicitTitle;

  const normalizedDataKeys = new Set(Object.keys(data).map(normalizeKey));
  const matchingType = TYPE_RULES.find((rule) =>
    rule.keys.some((key) => normalizedDataKeys.has(normalizeKey(key))),
  );
  return matchingType?.label ?? "Yêu cầu hỗ trợ";
}

export function getTicketSummary(ticket: Pick<TicketView, "dataJson">): string | null {
  const summary = valueByKey(ticket.dataJson ?? {}, SUMMARY_KEYS);
  if (!summary) return null;
  return summary.length > 96 ? `${summary.slice(0, 93).trimEnd()}…` : summary;
}
