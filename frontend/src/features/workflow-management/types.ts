import type {
  BuilderEdge,
  BuilderNode,
  WorkflowVersionStatus,
} from "@/features/workflow-builder";

export type WorkflowLifecycle = "ACTIVE" | "SUSPENDED" | "ARCHIVED";

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

export interface WorkflowSummary {
  id: string;
  key: string;
  name: string;
  description?: string | null;
  lifecycle: WorkflowLifecycle;
  ownerId: string;
  currentPublishedVersionId?: string | null;
  currentPublishedVersionNo?: number | null;
  lastPublishedAt?: string | null;
  activeDraftVersionId?: string | null;
  activeDraftVersionNo?: number | null;
  activeDraftRevision?: number | null;
  versionCount: number;
  createdAt: string;
  updatedAt: string;
  lockVersion: number;
}

export interface WorkflowDefinitionView {
  id: string;
  key: string;
  name: string;
  description?: string | null;
  lifecycle: WorkflowLifecycle;
  ownerId: string;
  currentPublishedVersionId?: string | null;
  activeDraftVersionId?: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  lockVersion: number;
}

export interface WorkflowVersionView {
  id: string;
  definitionId: string;
  versionNo: number;
  status: WorkflowVersionStatus;
  revision: number;
  checksum?: string | null;
  executionPackageJson?: unknown;
  basedOnVersionId?: string | null;
  rollbackOfVersionId?: string | null;
  createdBy: string;
  createdAt: string;
  publishedBy?: string | null;
  publishedAt?: string | null;
  lockVersion: number;
}

export interface WorkflowDetail {
  workflow: WorkflowSummary;
  versions: WorkflowVersionView[];
}

export interface BackendNodeView {
  id: string;
  workflowVersionId: string;
  nodeKey: string;
  nodeType: string;
  name: string;
  description?: string | null;
  configSchemaVersion: number;
  configJson?: Record<string, unknown> | null;
  inputSchemaJson?: unknown;
  outputSchemaJson?: unknown;
  positionJson?: { x?: number; y?: number } | null;
}

export interface BackendEdgeView {
  id: string;
  workflowVersionId: string;
  sourceNodeId: string;
  sourcePort: string;
  targetNodeId: string;
  conditionJson?: unknown;
  priority: number;
  defaultTransition: boolean;
  transitionType: "NORMAL" | "CONDITIONAL" | "REWORK" | "RETURN";
  label?: string | null;
  configJson?: Record<string, unknown> | null;
}

export interface BackendFormView {
  id: string;
  workflowVersionId: string;
  formKey: string;
  formType: "TICKET_FORM" | "TASK_FORM";
  schemaJson: CanonicalFormSchema;
  schemaChecksum: string;
}

export interface WorkflowVersionDetail {
  version: WorkflowVersionView;
  nodes: BackendNodeView[];
  edges: BackendEdgeView[];
  forms: BackendFormView[];
}

export interface RequestTypeAdminView {
  id: string;
  key: string;
  name: string;
  description?: string | null;
  category: string;
  active: boolean;
  creationPolicyJson: Record<string, unknown>;
  workflowDefinitionId: string;
  workflowDefinitionName: string;
  workflowLifecycle: WorkflowLifecycle;
  currentPublishedVersionId?: string | null;
  currentPublishedVersionNo?: number | null;
  schemaAvailable: boolean;
  createdAt: string;
  updatedAt: string;
  lockVersion: number;
}

export interface ValidationIssueView {
  code: string;
  severity: "ERROR" | "WARNING" | "ACK_REQUIRED_WARNING" | "INFO";
  resourceType: string;
  resourceId: string;
  fieldPath: string;
  message: string;
  suggestion?: string | null;
}

export interface ValidationView {
  workflowVersionId: string;
  revision: number;
  definitionChecksum: string;
  valid: boolean;
  publishable: boolean;
  issues: ValidationIssueView[];
}

export interface CanonicalFormSchema {
  formKey: string;
  formType: "TICKET_FORM";
  fields: CanonicalFormField[];
}

export interface CanonicalFormField {
  fieldId: string;
  key: string;
  label: string;
  description?: string | null;
  placeholder?: string | null;
  order: number;
  type: { type: string; nullable: boolean; itemType?: unknown };
  defaultValue?: unknown;
  sensitive: boolean;
  requirement: { mode: "ALWAYS" | "NEVER"; condition: null };
  visibility: { mode: "ALWAYS"; condition: null };
  editability: { mode: "EDITABLE"; condition: null };
  validation: {
    minimum: null;
    maximum: null;
    minimumLength: null;
    maximumLength: null;
    regex: null;
    safeRules: never[];
  };
  options?: {
    source: "STATIC";
    staticValues: unknown[];
    dataSourceKey: null;
  } | null;
  semantics: {
    participantCapable: boolean;
    businessSubject: boolean;
    filterable: boolean;
    reportable: boolean;
    searchable: boolean;
  };
}

export interface BuilderDocument {
  nodes: BuilderNode[];
  edges: BuilderEdge[];
}
