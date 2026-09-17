import { beforeEach, describe, expect, it, vi } from "vitest";

const { apiPut } = vi.hoisted(() => ({
  apiPut: vi.fn().mockResolvedValue({ draft: { revision: 1, lockVersion: 1 } }),
}));

vi.mock("@/shared/api/client", () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut,
}));

import { saveWorkflowGraph } from "./api";

describe("workflow management graph API", () => {
  beforeEach(() => {
    apiPut.mockClear();
  });

  it("omits optional null JSON fields from graph payloads", async () => {
    await saveWorkflowGraph(
      "workflow-1",
      "version-1",
      2,
      3,
      [
        {
          id: "node-1",
          workflowVersionId: "version-1",
          nodeKey: "start",
          nodeType: "START",
          name: "Start",
          configSchemaVersion: 1,
          configJson: {},
          inputSchemaJson: null,
          outputSchemaJson: null,
          positionJson: { x: 0, y: 0 },
        },
      ],
      [
        {
          id: "edge-1",
          workflowVersionId: "version-1",
          sourceNodeId: "node-1",
          sourcePort: "DEFAULT",
          targetNodeId: "node-1",
          conditionJson: null,
          priority: 0,
          defaultTransition: true,
          transitionType: "NORMAL",
          configJson: {},
        },
      ],
    );

    const body = apiPut.mock.calls[0][1];
    expect(body.graph.nodes[0]).not.toHaveProperty("inputSchemaJson");
    expect(body.graph.nodes[0]).not.toHaveProperty("outputSchemaJson");
    expect(body.graph.edges[0]).not.toHaveProperty("conditionJson");
  });
});
