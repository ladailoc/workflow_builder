import { apiGet, apiPost, apiPut } from "@/shared/api/client";
import {
  withRequiredNodeConfigDefaults,
  type FormSchema,
} from "@/features/workflow-builder";
import { toJsonObjectOrNull } from "./adapters";
import type {
  BackendEdgeView,
  BackendNodeView,
  CanonicalFormSchema,
  PageResult,
  RequestTypeAdminView,
  ValidationView,
  WorkflowDetail,
  WorkflowDefinitionView,
  WorkflowLifecycle,
  WorkflowSummary,
  WorkflowVersionDetail,
  WorkflowVersionView,
} from "./types";

function commandId(): string {
  return crypto.randomUUID();
}

export function fetchWorkflows(
  query = "",
): Promise<PageResult<WorkflowSummary>> {
  const suffix = query ? `?query=${encodeURIComponent(query)}` : "";
  return apiGet<PageResult<WorkflowSummary>>(`/api/v1/workflows${suffix}`);
}

export function fetchWorkflow(id: string): Promise<WorkflowDetail> {
  return apiGet<WorkflowDetail>(`/api/v1/workflows/${encodeURIComponent(id)}`);
}

export function createWorkflow(payload: {
  key: string;
  name: string;
  description?: string;
  ownerId: string;
}): Promise<WorkflowDefinitionView> {
  return apiPost(`/api/v1/workflows`, payload, {
    headers: { "X-Command-Id": commandId() },
  });
}

export function createWorkflowDraft(
  workflowId: string,
  basedOnVersionId?: string,
): Promise<WorkflowVersionView> {
  return apiPost(
    `/api/v1/workflows/${encodeURIComponent(workflowId)}/draft`,
    { basedOnVersionId: basedOnVersionId ?? null, rollbackOfVersionId: null },
    { headers: { "X-Command-Id": commandId() } },
  );
}

export function fetchWorkflowVersion(
  workflowId: string,
  versionId: string,
): Promise<WorkflowVersionDetail> {
  return apiGet(
    `/api/v1/workflows/${encodeURIComponent(workflowId)}/versions/${encodeURIComponent(versionId)}`,
  );
}

export function saveWorkflowGraph(
  workflowId: string,
  versionId: string,
  lockVersion: number,
  expectedRevision: number,
  nodes: BackendNodeView[],
  edges: BackendEdgeView[],
): Promise<{ draft: { revision: number; lockVersion: number } }> {
  const nodesPayload = nodes.map((node) => {
    const inputSchemaJson = toJsonObjectOrNull(node.inputSchemaJson);
    const outputSchemaJson = toJsonObjectOrNull(node.outputSchemaJson);

    return {
      clientRef: node.id,
      nodeKey: node.nodeKey,
      nodeType: node.nodeType,
      name: node.name,
      description: node.description ?? null,
      configSchemaVersion: node.configSchemaVersion,
      configJson: withRequiredNodeConfigDefaults(
        node.nodeType,
        toJsonObjectOrNull(node.configJson),
      ),
      ...(inputSchemaJson === null ? {} : { inputSchemaJson }),
      ...(outputSchemaJson === null ? {} : { outputSchemaJson }),
      positionJson: node.positionJson ?? {},
    };
  });

  const graph = {
    nodes: nodesPayload,
    edges: edges.map((edge) => {
      const conditionJson = toJsonObjectOrNull(edge.conditionJson);

      return {
        clientRef: edge.id,
        sourceClientRef: edge.sourceNodeId,
        sourcePort: edge.sourcePort,
        targetClientRef: edge.targetNodeId,
        ...(conditionJson === null ? {} : { conditionJson }),
        priority: edge.priority,
        defaultTransition: edge.defaultTransition,
        transitionType: edge.transitionType,
        label: edge.label ?? null,
        configJson: toJsonObjectOrNull(edge.configJson) ?? {},
      };
    }),
  };
  return apiPut(
    `/api/v1/workflows/${encodeURIComponent(workflowId)}/versions/${encodeURIComponent(versionId)}/graph`,
    { graph, expectedRevision },
    {
      headers: {
        "X-Command-Id": commandId(),
        "If-Match": String(lockVersion),
      },
    },
  );
}

export function saveTicketForm(
  workflowId: string,
  versionId: string,
  lockVersion: number,
  expectedRevision: number,
  schemaJson: CanonicalFormSchema,
): Promise<{ draft: { revision: number; lockVersion: number } }> {
  return apiPut(
    `/api/v1/workflows/${encodeURIComponent(workflowId)}/versions/${encodeURIComponent(versionId)}/ticket-form`,
    { schemaJson, expectedRevision },
    {
      headers: {
        "X-Command-Id": commandId(),
        "If-Match": String(lockVersion),
      },
    },
  );
}

export function validateWorkflow(
  workflowId: string,
  versionId: string,
): Promise<ValidationView> {
  return apiPost(
    `/api/v1/workflows/${encodeURIComponent(workflowId)}/versions/${encodeURIComponent(versionId)}/validate`,
    {},
  );
}

export function publishWorkflow(
  workflowId: string,
  versionId: string,
  lockVersion: number,
  expectedRevision: number,
  acknowledgedWarnings: readonly string[] = [],
): Promise<{
  workflowVersionId: string;
  versionNo: number;
  checksum: string;
  status: string;
}> {
  return apiPost(
    `/api/v1/workflows/${encodeURIComponent(workflowId)}/versions/${encodeURIComponent(versionId)}/publish`,
    { expectedRevision, acknowledgedWarnings },
    {
      headers: {
        "X-Command-Id": commandId(),
        "If-Match": String(lockVersion),
      },
    },
  );
}

export function cloneVersionAsDraft(
  workflowId: string,
  versionId: string,
  definitionLockVersion: number,
): Promise<{ draftVersionId: string; versionNo: number }> {
  return apiPost(
    `/api/v1/workflows/${encodeURIComponent(workflowId)}/versions/${encodeURIComponent(versionId)}/clone-as-draft`,
    {},
    {
      headers: {
        "X-Command-Id": commandId(),
        "If-Match": String(definitionLockVersion),
      },
    },
  );
}

export function changeWorkflowLifecycle(
  workflowId: string,
  action: "suspend" | "reactivate" | "archive",
  lockVersion: number,
  reason: string,
): Promise<WorkflowSummary> {
  return apiPost(
    `/api/v1/workflows/${encodeURIComponent(workflowId)}/${action}`,
    { reason },
    {
      headers: {
        "X-Command-Id": commandId(),
        "If-Match": String(lockVersion),
      },
    },
  );
}

export function fetchVersionDiff(
  workflowId: string,
  fromVersionId: string,
  toVersionId: string,
): Promise<Record<string, unknown>> {
  return apiGet(
    `/api/v1/workflows/${encodeURIComponent(workflowId)}/versions/${encodeURIComponent(fromVersionId)}/diff/${encodeURIComponent(toVersionId)}`,
  );
}

export function fetchAdminRequestTypes(): Promise<
  PageResult<RequestTypeAdminView>
> {
  return apiGet(`/api/v1/admin/request-types`);
}

export function fetchAdminRequestType(
  id: string,
): Promise<RequestTypeAdminView> {
  return apiGet(`/api/v1/admin/request-types/${encodeURIComponent(id)}`);
}

export function createRequestType(payload: {
  key: string;
  name: string;
  description?: string;
  category: string;
  workflowDefinitionId: string;
  active: boolean;
  creationPolicyJson: Record<string, unknown>;
}): Promise<RequestTypeAdminView> {
  return apiPost(`/api/v1/admin/request-types`, payload, {
    headers: { "X-Command-Id": commandId() },
  });
}

export function updateRequestType(
  id: string,
  lockVersion: number,
  payload: {
    name: string;
    description?: string;
    category: string;
    workflowDefinitionId: string;
    creationPolicyJson: Record<string, unknown>;
  },
): Promise<RequestTypeAdminView> {
  return apiPut(
    `/api/v1/admin/request-types/${encodeURIComponent(id)}`,
    payload,
    {
      headers: {
        "X-Command-Id": commandId(),
        "If-Match": String(lockVersion),
      },
    },
  );
}

export function setRequestTypeActive(
  id: string,
  active: boolean,
  lockVersion: number,
  reason: string,
): Promise<RequestTypeAdminView> {
  return apiPost(
    `/api/v1/admin/request-types/${encodeURIComponent(id)}/${active ? "activate" : "deactivate"}`,
    { reason },
    {
      headers: {
        "X-Command-Id": commandId(),
        "If-Match": String(lockVersion),
      },
    },
  );
}

export function toCanonicalForm(schema: FormSchema): CanonicalFormSchema {
  return {
    formKey: "ticket",
    formType: "TICKET_FORM",
    fields: schema.fields.map((field, order) => ({
      fieldId: field.fieldId ?? crypto.randomUUID(),
      key: field.key,
      label: field.label,
      description: field.description ?? null,
      placeholder: null,
      order,
      type: {
        type:
          field.type === "DECIMAL"
            ? "NUMBER"
            : field.type === "FILE"
              ? "FILE_REF"
              : field.type,
        nullable: !field.required,
      },
      defaultValue: field.defaultValue ?? null,
      sensitive: false,
      requirement: {
        mode: field.required ? "ALWAYS" : "NEVER",
        condition: null,
      },
      visibility: { mode: "ALWAYS", condition: null },
      editability: { mode: "EDITABLE", condition: null },
      validation: {
        minimum: null,
        maximum: null,
        minimumLength: null,
        maximumLength: null,
        regex: null,
        safeRules: [],
      },
      options:
        field.type === "ENUM"
          ? {
              source: "STATIC",
              staticValues: field.options ?? [],
              dataSourceKey: null,
            }
          : null,
      semantics: {
        participantCapable: false,
        businessSubject: false,
        filterable: false,
        reportable: false,
        searchable: false,
      },
    })),
  };
}

export function lifecycleLabel(value: WorkflowLifecycle): string {
  return value.charAt(0) + value.slice(1).toLowerCase();
}
