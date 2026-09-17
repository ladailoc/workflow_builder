import { describe, expect, it } from "vitest";
import { toBackendEdges, toBackendNodes, toBuilderNode } from "./adapters";
import type { BackendNodeView } from "./types";

const NODE: BackendNodeView = {
  id: "node-1",
  workflowVersionId: "version-1",
  nodeKey: "review",
  nodeType: "REVIEW",
  name: "Review",
  configSchemaVersion: 1,
  configJson: {},
  inputSchemaJson: "{}",
  outputSchemaJson: ["invalid"],
  positionJson: { x: 0, y: 0 },
};

describe("workflow graph adapters", () => {
  it("drops invalid optional schema values while loading a graph", () => {
    const node = toBuilderNode(NODE);

    expect(node.data.inputSchema).toBeNull();
    expect(node.data.outputSchema).toBeNull();
  });

  it("fills required defaults for human task nodes before saving", () => {
    const node = toBuilderNode({
      ...NODE,
      nodeType: "APPROVAL",
      configJson: {},
    });

    expect(node.data.config).toEqual(
      expect.objectContaining({
        participant: expect.objectContaining({ type: "MANAGER_OF" }),
        allowedActions: ["APPROVED", "REJECTED"],
      }),
    );

    const [backendNode] = toBackendNodes("version-1", [node]);
    expect(backendNode.configJson).toEqual(
      expect.objectContaining({
        participant: expect.objectContaining({ type: "MANAGER_OF" }),
        allowedActions: ["APPROVED", "REJECTED"],
      }),
    );
  });

  it("sends only JSON objects for optional graph schema fields", () => {
    const node = toBuilderNode(NODE);
    const [backendNode] = toBackendNodes("version-1", [node]);
    const [backendEdge] = toBackendEdges("version-1", [
      {
        id: "edge-1",
        source: "node-1",
        target: "node-1",
        sourceHandle: "DEFAULT",
        data: {
          condition: "invalid",
          config: ["invalid"],
        },
      },
    ]);

    expect(backendNode.inputSchemaJson).toBeNull();
    expect(backendNode.outputSchemaJson).toBeNull();
    expect(backendEdge.conditionJson).toBeNull();
    expect(backendEdge.configJson).toEqual({});
  });
});
