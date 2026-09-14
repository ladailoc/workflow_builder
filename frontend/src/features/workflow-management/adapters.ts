import {
  getNodeManifest,
  type BuilderEdge,
  type BuilderNode,
  type BuilderNodeType,
  type FormFieldType,
  type FormSchema,
  type WorkflowVersionDto,
} from "@/features/workflow-builder";
import type {
  BackendEdgeView,
  BackendNodeView,
  WorkflowVersionDetail,
} from "./types";

export function toBuilderVersion(
  detail: WorkflowVersionDetail,
): WorkflowVersionDto {
  return {
    id: detail.version.id,
    definitionId: detail.version.definitionId,
    versionNo: detail.version.versionNo,
    status: detail.version.status,
    revision: detail.version.revision,
    nodes: detail.nodes.map(toBuilderNode),
    edges: detail.edges.map(toBuilderEdge),
  };
}

export function toBuilderNode(node: BackendNodeView): BuilderNode {
  const nodeType = node.nodeType as BuilderNodeType;
  return {
    id: node.id,
    type: "workflowNode",
    position: {
      x: node.positionJson?.x ?? 120,
      y: node.positionJson?.y ?? 120,
    },
    data: {
      key: node.nodeKey,
      label: node.name,
      nodeType,
      outputPorts: [...(getNodeManifest(nodeType)?.outputPorts ?? [])],
      config: node.configJson ?? {},
      readOnly: false,
    },
  };
}

export function toBuilderEdge(edge: BackendEdgeView): BuilderEdge {
  return {
    id: edge.id,
    source: edge.sourceNodeId,
    target: edge.targetNodeId,
    sourceHandle: edge.sourcePort,
    label: edge.label ?? undefined,
    data: {
      condition: edge.conditionJson ?? undefined,
      priority: edge.priority,
      isDefault: edge.defaultTransition,
      transitionType: edge.transitionType,
      config: edge.configJson ?? {},
    },
  };
}

export function toBackendNodes(
  versionId: string,
  nodes: BuilderNode[],
): BackendNodeView[] {
  return nodes.map((node) => ({
    id: node.id,
    workflowVersionId: versionId,
    nodeKey: node.data.key,
    nodeType: node.data.nodeType,
    name: node.data.label,
    description:
      typeof node.data.description === "string" ? node.data.description : null,
    configSchemaVersion:
      typeof node.data.configSchemaVersion === "number"
        ? node.data.configSchemaVersion
        : 1,
    configJson: node.data.config,
    inputSchemaJson: node.data.inputSchema ?? null,
    outputSchemaJson: node.data.outputSchema ?? null,
    positionJson: node.position,
  }));
}

export function toBackendEdges(
  versionId: string,
  edges: BuilderEdge[],
): BackendEdgeView[] {
  return edges.map((edge, index) => ({
    id: edge.id,
    workflowVersionId: versionId,
    sourceNodeId: edge.source,
    sourcePort: edge.sourceHandle ?? "DEFAULT",
    targetNodeId: edge.target,
    conditionJson: edge.data?.condition ?? null,
    priority:
      typeof edge.data?.priority === "number" ? edge.data.priority : index,
    defaultTransition: edge.data?.isDefault === true,
    transitionType:
      edge.data?.transitionType === "CONDITIONAL" ||
      edge.data?.transitionType === "REWORK" ||
      edge.data?.transitionType === "RETURN"
        ? edge.data.transitionType
        : "NORMAL",
    label: typeof edge.label === "string" ? edge.label : null,
    configJson:
      edge.data?.config && typeof edge.data.config === "object"
        ? (edge.data.config as Record<string, unknown>)
        : {},
  }));
}

export function toBuilderForm(detail: WorkflowVersionDetail): FormSchema {
  const form = detail.forms.find(
    (candidate) => candidate.formType === "TICKET_FORM",
  );
  if (!form) return { fields: [] };
  return {
    fields: form.schemaJson.fields.map((field) => ({
      fieldId: field.fieldId,
      key: field.key,
      label: field.label,
      description: field.description ?? undefined,
      type: toBuilderFieldType(field.type.type),
      required: field.requirement.mode === "ALWAYS",
      options:
        field.options?.source === "STATIC"
          ? field.options.staticValues.map(String)
          : undefined,
      defaultValue: field.defaultValue,
    })),
  };
}

function toBuilderFieldType(type: string): FormFieldType {
  if (type === "NUMBER" || type === "MONEY") return "DECIMAL";
  if (type === "FILE_REF") return "FILE";
  if (
    type === "STRING" ||
    type === "INTEGER" ||
    type === "BOOLEAN" ||
    type === "DATE" ||
    type === "ENUM"
  ) {
    return type;
  }
  return "STRING";
}
