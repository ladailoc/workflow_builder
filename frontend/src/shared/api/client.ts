import type { ApiError } from "@/shared/types/api";

export const API_PREFIX = "/api/v1" as const;

const configuredBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL?.replace(
  /\/$/,
  "",
);

export function apiUrl(path: `/${string}`): string {
  if (path.startsWith("/api/")) {
    const rootBase = configuredBaseUrl ?? "";
    return `${rootBase}${path}`;
  }
  const baseUrl = configuredBaseUrl ?? API_PREFIX;
  return `${baseUrl}${path}`;
}

export interface ClientActorInfo {
  actorId: string;
  principalName?: string;
  roles?: readonly string[];
  permissions?: readonly string[];
}

let currentActorInfo: ClientActorInfo | null = {
  actorId: "10000000-0000-4000-8000-000000000001",
  principalName: "Alice User",
  roles: ["USER"],
  permissions: ["REQUEST_CATALOG_ACCESS", "TASK_ACTION_ACCESS"],
};

export function setActiveApiActor(actor: ClientActorInfo | null): void {
  currentActorInfo = actor;
}

export function getActiveApiActor(): ClientActorInfo | null {
  return currentActorInfo;
}

export interface ApiRequestOptions extends Omit<RequestInit, "body"> {
  actorId?: string;
  commandId?: string;
  correlationId?: string;
  body?: unknown;
}

export class ApiRequestError extends Error {
  public readonly code: string;
  public readonly field?: string;
  public readonly details?: unknown;

  constructor(
    public readonly status: number,
    public readonly payload: ApiError | undefined,
  ) {
    super(payload?.message ?? `API request failed with status ${status}`);
    this.name = "ApiRequestError";
    this.code = payload?.code ?? `HTTP_${status}`;
    this.field = payload?.field;
    this.details = payload?.details;
  }
}

async function request<T>(
  path: `/${string}`,
  options: ApiRequestOptions = {},
): Promise<T> {
  const { actorId, commandId, correlationId, body, headers, ...init } = options;

  const requestHeaders = new Headers(headers);
  if (!requestHeaders.has("Accept")) {
    requestHeaders.set("Accept", "application/json");
  }

  if (body !== undefined && !(body instanceof FormData)) {
    requestHeaders.set("Content-Type", "application/json");
  }

  const effectiveActorId = actorId ?? currentActorInfo?.actorId;
  if (effectiveActorId && !requestHeaders.has("X-Actor-Id")) {
    requestHeaders.set("X-Actor-Id", effectiveActorId);
  }
  if (currentActorInfo?.principalName && !requestHeaders.has("X-Actor-Name")) {
    requestHeaders.set("X-Actor-Name", currentActorInfo.principalName);
  }
  if (
    currentActorInfo?.roles &&
    currentActorInfo.roles.length > 0 &&
    !requestHeaders.has("X-Actor-Roles")
  ) {
    requestHeaders.set("X-Actor-Roles", currentActorInfo.roles.join(","));
  }
  if (
    currentActorInfo?.permissions &&
    currentActorInfo.permissions.length > 0 &&
    !requestHeaders.has("X-Actor-Permissions")
  ) {
    requestHeaders.set("X-Actor-Permissions", currentActorInfo.permissions.join(","));
  }

  const effectiveCorrelationId =
    correlationId ??
    requestHeaders.get("X-Correlation-Id") ??
    (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : undefined);
  if (effectiveCorrelationId && !requestHeaders.has("X-Correlation-Id")) {
    requestHeaders.set("X-Correlation-Id", effectiveCorrelationId);
  }

  if (commandId) {
    requestHeaders.set("X-Command-Id", commandId);
  } else if (
    !requestHeaders.has("X-Command-Id") &&
    init.method &&
    ["POST", "PUT", "PATCH"].includes(init.method.toUpperCase()) &&
    typeof crypto !== "undefined" &&
    typeof crypto.randomUUID === "function"
  ) {
    requestHeaders.set("X-Command-Id", crypto.randomUUID());
  }

  const url = apiUrl(path);
  const response = await fetch(url, {
    ...init,
    headers: requestHeaders,
    body:
      body === undefined
        ? undefined
        : body instanceof FormData
          ? body
          : JSON.stringify(body),
  });

  if (!response.ok) {
    let payload: ApiError | undefined;
    try {
      payload = await response.json();
    } catch {
      // Body not JSON
    }
    throw new ApiRequestError(response.status, payload);
  }

  if (response.status === 204) {
    return undefined as unknown as T;
  }

  const contentType = response.headers.get("content-type");
  if (contentType?.includes("application/json")) {
    return response.json();
  }

  return response.text() as unknown as T;
}

export const apiClient = {
  get: <T>(path: `/${string}`, options?: ApiRequestOptions) =>
    request<T>(path, { ...options, method: "GET" }),

  post: <T>(path: `/${string}`, body?: unknown, options?: ApiRequestOptions) =>
    request<T>(path, { ...options, method: "POST", body }),

  put: <T>(path: `/${string}`, body?: unknown, options?: ApiRequestOptions) =>
    request<T>(path, { ...options, method: "PUT", body }),

  patch: <T>(path: `/${string}`, body?: unknown, options?: ApiRequestOptions) =>
    request<T>(path, { ...options, method: "PATCH", body }),

  delete: <T>(path: `/${string}`, options?: ApiRequestOptions) =>
    request<T>(path, { ...options, method: "DELETE" }),
};

export const apiGet = apiClient.get;
export const apiPost = apiClient.post;
export const apiPut = apiClient.put;
export const apiPatch = apiClient.patch;
export const apiDelete = apiClient.delete;

