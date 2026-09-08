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

  if (actorId) {
    requestHeaders.set("X-Actor-Id", actorId);
  }
  if (commandId) {
    requestHeaders.set("X-Command-Id", commandId);
  }
  if (correlationId) {
    requestHeaders.set("X-Correlation-Id", correlationId);
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

