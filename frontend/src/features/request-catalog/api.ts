import { apiGet, apiPost } from "@/shared/api/client";
import type {
  CatalogItem,
  CreateDraftPayload,
  CreateSchemaResponse,
  CreatedTicketAggregate,
  SubmitTicketPayload,
} from "./types";

export async function fetchRequestTypes(): Promise<CatalogItem[]> {
  return apiGet<CatalogItem[]>("/api/v1/request-types");
}

export async function fetchCreateSchema(
  key: string,
): Promise<CreateSchemaResponse> {
  return apiGet<CreateSchemaResponse>(
    `/api/v1/request-types/${encodeURIComponent(key)}/create-schema`,
  );
}

export async function createTicketDraft(
  payload: CreateDraftPayload,
  commandId?: string,
): Promise<CreatedTicketAggregate> {
  const cid = commandId ?? (typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : "cmd-" + Date.now());
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
  const cid = commandId ?? (typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : "cmd-" + Date.now());
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
