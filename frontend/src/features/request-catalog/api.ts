import { apiGet, apiPost } from "@/shared/api/client";
import type {
  CatalogItem,
  CreateDraftPayload,
  CreateSchemaResponse,
  CreatedTicketAggregate,
  CreatedCategoryTicket,
  SubmitTicketPayload,
} from "./types";

export async function fetchRequestTypes(): Promise<CatalogItem[]> {
  const items = await apiGet<
    Array<{
      categoryKey: string;
      name: string;
      description?: string;
      categoryGroup?: string;
      icon?: string;
    }>
  >("/api/v1/ticket-categories?active=true");
  return items.map((item) => ({
    id: item.categoryKey,
    key: item.categoryKey,
    categoryKey: item.categoryKey,
    name: item.name,
    description: item.description ?? "",
    category: item.categoryGroup ?? "General",
    icon: item.icon ?? null,
  }));
}

export async function fetchCreateSchema(
  key: string,
  tenantId?: string,
): Promise<CreateSchemaResponse> {
  const contract = await apiGet<{
    categoryKey: string;
    categoryVersionId: string;
    categoryChecksum: string;
    formVersionId: string;
    formVersionNo: number;
    formChecksum: string;
    form: CreateSchemaResponse["ticketFormSchema"];
    workflowVersionId: string;
    workflowVersionNo: number;
    workflowChecksum: string;
    mappingChecksum: string;
    tenantId?: string | null;
    bindingScope?: "DEFAULT" | "OVERRIDE";
  }>(
    `/api/v1/ticket-categories/${encodeURIComponent(key)}/create-contract${tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ""}`,
  );
  return {
    requestTypeId: contract.categoryVersionId,
    requestTypeKey: contract.categoryKey,
    sourceWorkflowVersionId: contract.workflowVersionId,
    formSchemaVersion: contract.formVersionNo,
    formSchemaChecksum: contract.formChecksum,
    ticketFormSchema: contract.form,
    categoryKey: contract.categoryKey,
    categoryVersionId: contract.categoryVersionId,
    categoryChecksum: contract.categoryChecksum,
    formVersionId: contract.formVersionId,
    mappingChecksum: contract.mappingChecksum,
    tenantId: contract.tenantId ?? tenantId ?? null,
    bindingScope: contract.bindingScope,
  };
}

export async function createCategoryTicket(
  categoryKey: string,
  schema: CreateSchemaResponse,
  formData: Record<string, unknown>,
  commandId?: string,
): Promise<CreatedCategoryTicket> {
  if (
    !schema.categoryChecksum ||
    !schema.formVersionId ||
    !schema.mappingChecksum
  )
    throw new Error("Category create contract is incomplete");
  const cid =
    commandId ??
    (typeof crypto !== "undefined" && crypto.randomUUID
      ? crypto.randomUUID()
      : "cmd-" + Date.now());
  return apiPost<CreatedCategoryTicket>(
    `/api/v1/ticket-categories/${encodeURIComponent(categoryKey)}/tickets`,
    {
      formData,
      categoryChecksum: schema.categoryChecksum,
      formVersionId: schema.formVersionId,
      formChecksum: schema.formSchemaChecksum,
      mappingChecksum: schema.mappingChecksum,
      tenantId: schema.tenantId ?? null,
    },
    { headers: { "X-Command-Id": cid } },
  );
}

export async function createTicketDraft(
  payload: CreateDraftPayload,
  commandId?: string,
): Promise<CreatedTicketAggregate> {
  const cid =
    commandId ??
    (typeof crypto !== "undefined" && crypto.randomUUID
      ? crypto.randomUUID()
      : "cmd-" + Date.now());
  return apiPost<CreatedTicketAggregate>("/api/v1/tickets/drafts", payload, {
    headers: {
      "X-Command-Id": cid,
    },
  });
}

export async function submitTicket(
  ticketId: string,
  lockVersion: number,
  payload: SubmitTicketPayload,
  commandId?: string,
): Promise<CreatedTicketAggregate> {
  const cid =
    commandId ??
    (typeof crypto !== "undefined" && crypto.randomUUID
      ? crypto.randomUUID()
      : "cmd-" + Date.now());
  return apiPost<CreatedTicketAggregate>(
    `/api/v1/tickets/${ticketId}/submit`,
    payload,
    {
      headers: {
        "X-Command-Id": cid,
        "If-Match": String(lockVersion),
      },
    },
  );
}
