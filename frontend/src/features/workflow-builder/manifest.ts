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
] as const;

export const NODE_CATALOG: readonly NodeSchemaManifest[] = [
  {
    type: "START",
    name: "Start",
    category: "Control",
    description: "Initial activation node for the workflow instance.",
    outputPorts: ["DEFAULT"],
    color: "bg-emerald-500 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [...RUNTIME_CONFIG_PROPERTIES],
    requiredConfigProperties: [],
  },
  {
    type: "END",
    name: "End",
    category: "Control",
    description: "Terminal node completing execution of this path.",
    outputPorts: [],
    color: "bg-slate-700 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: ["outcome", ...RUNTIME_CONFIG_PROPERTIES],
    requiredConfigProperties: [],
  },
  {
    type: "APPROVAL",
    name: "Approval",
    category: "Human",
    description: "Human task with single or multi-instance decision resolution.",
    outputPorts: ["APPROVED", "REJECTED"],
    color: "bg-blue-600 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [
      "participant",
      "formKey",
      "allowedActions",
      "priority",
      "titleSnapshot",
      "descriptionSnapshot",
      "failurePolicy",
      "taskGenerationMode",
      "completionPolicy",
      "fallbackChain",
      "stepFormSchema",
      "formSchema",
      ...RUNTIME_CONFIG_PROPERTIES,
    ],
    requiredConfigProperties: ["participant", "allowedActions"],
  },
  {
    type: "REVIEW",
    name: "Review",
    category: "Human",
    description: "Human verification or document check task.",
    outputPorts: ["APPROVED", "REJECTED"],
    color: "bg-sky-600 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [
      "participant",
      "formKey",
      "allowedActions",
      "priority",
      "titleSnapshot",
      "descriptionSnapshot",
      "failurePolicy",
      "taskGenerationMode",
      "completionPolicy",
      "fallbackChain",
      "stepFormSchema",
      "formSchema",
      ...RUNTIME_CONFIG_PROPERTIES,
    ],
    requiredConfigProperties: ["participant", "allowedActions"],
  },
  {
    type: "CONDITION",
    name: "Condition",
    category: "Routing",
    description: "Evaluates boolean expression to branch execution paths.",
    outputPorts: ["TRUE", "FALSE"],
    color: "bg-amber-500 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: ["expression", ...RUNTIME_CONFIG_PROPERTIES],
    requiredConfigProperties: ["expression"],
  },
  {
    type: "PARALLEL_SPLIT",
    name: "Parallel Split",
    category: "Routing",
    description: "Forks concurrent execution across multiple branches.",
    outputPorts: ["BRANCH_1", "BRANCH_2"],
    color: "bg-indigo-600 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [...RUNTIME_CONFIG_PROPERTIES],
    requiredConfigProperties: [],
  },
  {
    type: "JOIN",
    name: "Join",
    category: "Routing",
    description: "Synchronizes concurrent inbound branches.",
    outputPorts: ["DEFAULT"],
    color: "bg-indigo-500 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [
      "policy",
      "threshold",
      "joinScopeId",
      "remainingBranchPolicy",
      ...RUNTIME_CONFIG_PROPERTIES,
    ],
    requiredConfigProperties: [],
  },
  {
    type: "SYSTEM_ACTION",
    name: "System Action",
    category: "Integration",
    description: "Executes allowlisted Connector Action Version.",
    outputPorts: ["SUCCESS", "ERROR"],
    color: "bg-violet-600 text-white",
    configSchemaVersion: 1,
    allowedConfigProperties: [
      "connectorKey",
      "actionKey",
      "actionVersion",
      "credentialRef",
      "retryPolicy",
      "failureAction",
      ...RUNTIME_CONFIG_PROPERTIES,
    ],
    requiredConfigProperties: ["connectorKey", "actionKey", "actionVersion"],
  },
  {
    type: "SUB_WORKFLOW",
    name: "Sub-Workflow",
    category: "Integration",
    description: "Spawns child workflow event with Wait/Fire policy.",
    outputPorts: ["COMPLETED", "FAILED"],
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
    name: "Notification",
    category: "Communication",
    description: "Dispatches durable email or in-app message.",
    outputPorts: ["DEFAULT"],
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

export function getNodeManifest(type: string): NodeSchemaManifest | undefined {
  return NODE_CATALOG.find((item) => item.type === type);
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
