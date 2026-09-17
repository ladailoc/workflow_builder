import type { BuilderNodeType, NodeCatalogItem } from "./types";

export interface NodeSchemaManifest extends NodeCatalogItem {
  configSchemaVersion: number;
  allowedConfigProperties: string[];
  requiredConfigProperties: string[];
}

export const RUNTIME_CONFIG_PROPERTIES: readonly string[] = [
  "inputBindings",
  "variableMappings",
  "routingMode",
  "multiInstance",
  "sla",
  "taskAggregation",
  "failure",
] as const;

export const NODE_CATALOG: readonly NodeSchemaManifest[] = [
  {
    type: "START",
    name: "Bắt đầu",
    category: "Control",
    description: "Kích hoạt ban đầu cho một phiên chạy quy trình.",
    outputPorts: ["STARTED"],
    color: "bg-emerald-500 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [...RUNTIME_CONFIG_PROPERTIES],
    requiredConfigProperties: [],
  },
  {
    type: "END",
    name: "Kết thúc",
    category: "Control",
    description: "Kết thúc việc xử lý của nhánh này.",
    outputPorts: [],
    color: "bg-slate-700 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: ["outcome", ...RUNTIME_CONFIG_PROPERTIES],
    requiredConfigProperties: [],
  },
  {
    type: "APPROVAL",
    name: "Phê duyệt",
    category: "Human",
    description: "Công việc cần một hoặc nhiều người đưa ra quyết định.",
    outputPorts: ["APPROVED", "REJECTED", "REVISION_REQUESTED"],
    color: "bg-blue-600 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [
      "participant",
      "formKey",
      "allowedActions",
      ...RUNTIME_CONFIG_PROPERTIES,
    ],
    requiredConfigProperties: ["participant", "allowedActions"],
  },
  {
    type: "REVIEW",
    name: "Kiểm tra",
    category: "Human",
    description: "Công việc kiểm tra thông tin hoặc tài liệu.",
    outputPorts: ["SUBMITTED", "RETURNED"],
    color: "bg-sky-600 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [
      "participant",
      "formKey",
      "allowedActions",
      ...RUNTIME_CONFIG_PROPERTIES,
    ],
    requiredConfigProperties: ["participant", "allowedActions"],
  },
  {
    type: "CONDITION",
    name: "Điều kiện",
    category: "Routing",
    description: "Đánh giá biểu thức đúng/sai để rẽ sang các nhánh xử lý.",
    outputPorts: ["TRUE", "FALSE", "ERROR"],
    color: "bg-amber-500 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: ["expression", ...RUNTIME_CONFIG_PROPERTIES],
    requiredConfigProperties: ["expression"],
  },
  {
    type: "PARALLEL_SPLIT",
    name: "Tách nhánh song song",
    category: "Routing",
    description: "Chia việc xử lý đồng thời thành nhiều nhánh.",
    outputPorts: ["SPLIT"],
    color: "bg-indigo-600 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [...RUNTIME_CONFIG_PROPERTIES],
    requiredConfigProperties: [],
  },
  {
    type: "JOIN",
    name: "Hợp nhất",
    category: "Routing",
    description: "Đồng bộ các nhánh xử lý đi vào.",
    outputPorts: ["DEFAULT"],
    color: "bg-indigo-500 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [
      "policy",
      "threshold",
      "remainingBranchPolicy",
      ...RUNTIME_CONFIG_PROPERTIES,
    ],
    requiredConfigProperties: [],
  },
  {
    type: "SYSTEM_ACTION",
    name: "Tác vụ hệ thống",
    category: "Integration",
    description:
      "Thực hiện một tác vụ đã được cấu hình để kết nối với hệ thống khác.",
    outputPorts: ["SUCCESS", "ERROR"],
    color: "bg-violet-600 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [
      "connectorKey",
      "actionKey",
      "actionVersion",
      "credentialRef",
      ...RUNTIME_CONFIG_PROPERTIES,
    ],
    requiredConfigProperties: ["connectorKey", "actionKey", "actionVersion"],
  },
  {
    type: "SUB_WORKFLOW",
    name: "Quy trình con",
    category: "Integration",
    description:
      "Tạo sự kiện quy trình con theo chính sách chờ hoặc chạy tiếp.",
    outputPorts: ["COMPLETED", "FAILED", "CANCELLED"],
    color: "bg-purple-600 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [
      "childWorkflowDefinitionKey",
      "executionMode",
      "cancellationPolicy",
      "inputMappings",
      "outputMappings",
      ...RUNTIME_CONFIG_PROPERTIES,
    ],
    requiredConfigProperties: ["childWorkflowDefinitionKey"],
  },
  {
    type: "NOTIFICATION",
    name: "Thông báo",
    category: "Communication",
    description: "Gửi email hoặc thông báo trong ứng dụng có lưu vết.",
    outputPorts: ["QUEUED"],
    color: "bg-teal-600 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [
      "channel",
      "participant",
      "template",
      "maxAttempts",
      "allowAfterTerminal",
      ...RUNTIME_CONFIG_PROPERTIES,
    ],
    requiredConfigProperties: ["channel", "participant", "template"],
  },
] as const;

function isConfigObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Supplies safe values for required properties whose defaults are unambiguous.
 * Nodes that need an external identifier (connector or child workflow) remain
 * incomplete until the user configures them explicitly.
 */
export function withRequiredNodeConfigDefaults(
  type: string,
  config: Record<string, unknown> | null | undefined,
): Record<string, unknown> {
  const next = { ...(config ?? {}) };

  if (type === "APPROVAL" || type === "REVIEW") {
    if (!isConfigObject(next.participant)) {
      next.participant = {
        type: "MANAGER_OF",
        depth: 1,
        cardinality: "SINGLE",
        taskGenerationMode: "ONE_PER_PARTICIPANT",
        completionPolicy: "FIRST_RESPONSE",
      };
    }
    if (!Array.isArray(next.allowedActions)) {
      next.allowedActions =
        type === "APPROVAL"
          ? ["APPROVED", "REJECTED"]
          : ["SUBMITTED", "RETURNED"];
    }
  }

  if (type === "NOTIFICATION") {
    if (typeof next.channel !== "string" || next.channel.trim() === "") {
      next.channel = "IN_APP";
    }
    if (!isConfigObject(next.participant)) {
      next.participant = { type: "CREATOR" };
    }
    if (!isConfigObject(next.template)) {
      next.template = {
        title: "Yêu cầu đã được cập nhật",
        body: "Yêu cầu của bạn đã được cập nhật.",
      };
    }
  }

  return next;
}

export function getNodeManifest(type: string): NodeSchemaManifest | undefined {
  return NODE_CATALOG.find((item) => item.type === type);
}

const LEGACY_NODE_LABELS: Record<string, string[]> = {
  START: ["START", "Start", "Start Node", "Start Task"],
  END: ["END", "End", "End Node", "End Task"],
  APPROVAL: ["APPROVAL", "Approval", "Approval Task"],
  REVIEW: ["REVIEW", "Review", "Review Task"],
  CONDITION: ["CONDITION", "Condition", "Condition Task"],
  PARALLEL_SPLIT: ["PARALLEL_SPLIT", "Parallel Split", "Parallel Split Task"],
  JOIN: ["JOIN", "Join", "Join Task"],
  SYSTEM_ACTION: ["SYSTEM_ACTION", "System Action", "System Action Task"],
  SUB_WORKFLOW: ["SUB_WORKFLOW", "SubWorkflow", "Sub Workflow", "Sub-Workflow"],
  NOTIFICATION: ["NOTIFICATION", "Notification", "Notification Task"],
};

/**
 * Shows the Vietnamese manifest name for nodes created by older versions
 * while preserving a label that a user deliberately customized.
 */
export function getNodeDisplayName(
  type: string,
  label?: string | null,
): string {
  const trimmedLabel = label?.trim();
  const legacyLabels = LEGACY_NODE_LABELS[type] ?? [];

  if (!trimmedLabel || legacyLabels.includes(trimmedLabel)) {
    return getNodeManifest(type)?.name ?? trimmedLabel ?? type;
  }

  return trimmedLabel;
}

const PORT_LABELS: Record<string, string> = {
  STARTED: "Đã bắt đầu",
  APPROVED: "Đã phê duyệt",
  REJECTED: "Từ chối",
  REVISION_REQUESTED: "Yêu cầu bổ sung",
  SUBMITTED: "Đã gửi",
  RETURNED: "Trả lại",
  TRUE: "Đúng",
  FALSE: "Sai",
  ERROR: "Lỗi",
  SPLIT: "Tách nhánh",
  DEFAULT: "Mặc định",
  SUCCESS: "Thành công",
  COMPLETED: "Hoàn tất",
  FAILED: "Thất bại",
  CANCELLED: "Đã hủy",
  QUEUED: "Đang chờ gửi",
};

export function formatPortLabel(port: string): string {
  return PORT_LABELS[port] ?? port.replaceAll("_", " ");
}

export function findUnknownConfigProperties(
  type: BuilderNodeType,
  config: Record<string, unknown>,
): string[] {
  const manifest = getNodeManifest(type);
  if (!manifest) return [];
  const allowed = new Set(manifest.allowedConfigProperties);
  return Object.keys(config).filter((key) => !allowed.has(key));
}
