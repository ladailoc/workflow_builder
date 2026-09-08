import type { ApiError } from "@/shared/types/api";

export const API_PREFIX = "/api/v1" as const;

const configuredBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL?.replace(
  /\/$/,
  "",
);

export function apiUrl(path: `/${string}`): string {
  const baseUrl = configuredBaseUrl ?? API_PREFIX;
  return `${baseUrl}${path}`;
}

export class ApiRequestError extends Error {
  constructor(
    public readonly status: number,
    public readonly payload: ApiError | undefined,
  ) {
    super(payload?.message ?? `API request failed with status ${status}`);
    this.name = "ApiRequestError";
  }
}
