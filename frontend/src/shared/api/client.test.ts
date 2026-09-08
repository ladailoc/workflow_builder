import { describe, expect, it, vi, beforeEach } from "vitest";
import { apiClient, apiUrl, ApiRequestError } from "./client";

describe("API Client", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("builds correct URL with default API prefix", () => {
    expect(apiUrl("/catalog/request-types")).toBe("/api/v1/catalog/request-types");
  });

  it("performs GET request and attaches headers", async () => {
    const mockData = { items: [{ id: "req-1" }] };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ "content-type": "application/json" }),
      json: async () => mockData,
    });

    const result = await apiClient.get<{ items: { id: string }[] }>(
      "/catalog/request-types",
      { actorId: "actor-123", correlationId: "corr-456" },
    );

    expect(result).toEqual(mockData);
    expect(global.fetch).toHaveBeenCalledWith(
      "/api/v1/catalog/request-types",
      expect.objectContaining({
        method: "GET",
        headers: expect.any(Headers),
      }),
    );
  });

  it("handles 204 No Content gracefully", async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 204,
      headers: new Headers(),
    });

    const result = await apiClient.delete("/tickets/123");
    expect(result).toBeUndefined();
  });

  it("throws ApiRequestError on non-ok responses", async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      headers: new Headers({ "content-type": "application/json" }),
      json: async () => ({
        code: "WORKFLOW_DRAFT_REVISION_CONFLICT",
        message: "Stale revision",
      }),
    });

    await expect(apiClient.post("/tickets", { title: "Test" })).rejects.toThrow(
      ApiRequestError,
    );
  });
});
