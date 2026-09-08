export type BuilderNodeType =
  | "START"
  | "END"
  | "APPROVAL"
  | "REVIEW"
  | "CONDITION"
  | "PARALLEL_SPLIT"
  | "JOIN"
  | "SYSTEM_ACTION"
  | "SUB_WORKFLOW"
  | "NOTIFICATION";

export type WorkflowVersionStatus =
  | "DRAFT"
  | "PUBLISHED"
  | "SUPERSEDED"
  | "ARCHIVED";

import type { Node, Edge } from "@xyflow/react";

export interface NodeCatalogItem {
  type: BuilderNodeType;
  name: string;
  category: "Control" | "Human" | "Routing" | "Integration" | "Communication";
  description: string;
  outputPorts: string[];
  color: string;
}

export interface NodeData extends Record<string, unknown> {
  key: string;
  label: string;
  nodeType: BuilderNodeType;
  outputPorts: string[];
  config: Record<string, unknown>;
  readOnly?: boolean;
}

export type BuilderNode = Node<NodeData, "workflowNode">;
export type BuilderEdge = Edge;

export interface ValidationIssue {
  id: string;
  code?: string;
  nodeId?: string;
  edgeId?: string;
  field?: string;
  severity: "ERROR" | "WARNING";
  message: string;
}

export interface WorkflowVersionDto {
  id: string;
  definitionId: string;
  versionNo: number;
  status: WorkflowVersionStatus;
  revision: number;
  nodes: BuilderNode[];
  edges: BuilderEdge[];
}
