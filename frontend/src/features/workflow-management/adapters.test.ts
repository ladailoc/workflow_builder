import { describe, expect, it } from "vitest";
import {
  toBackendEdges,
  toBackendNodes,
  toBuilderEdge,
  toBuilderNode,
} from "./adapters";
import type { BackendEdgeView, BackendNodeView } from "./types";

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

  it("round-trips a revision rework edge and its bounded policy", () => {
    const backendEdge: BackendEdgeView = {
      id: "edge-revision-review",
      workflowVersionId: "version-1",
      sourceNodeId: "node-approval",
      sourcePort: "REVISION_REQUESTED",
      targetNodeId: "node-review",
      priority: 2,
      defaultTransition: false,
      transitionType: "REWORK",
      configJson: {
        reworkPolicy: {
          maxIterations: 4,
          onExhausted: "FAIL_EVENT",
          scope: "WHOLE_NODE",
        },
        rollbackStrategy: "RESTORE_ORIGINAL",
      },
    };

    const builderEdge = toBuilderEdge(backendEdge);
    expect(builderEdge.data?.reworkConfig).toEqual(
      expect.objectContaining({
        targetStepId: "node-review",
        maxIterations: 4,
        rollbackStrategy: "RESTORE_ORIGINAL",
        multiInstanceScope: "WHOLE_NODE",
      }),
    );

    const [savedEdge] = toBackendEdges("version-1", [builderEdge]);
    expect(savedEdge.transitionType).toBe("REWORK");
    expect(savedEdge.sourcePort).toBe("REVISION_REQUESTED");
    expect(savedEdge.configJson?.reworkPolicy).toEqual(
      expect.objectContaining({
        maxIterations: 4,
        onExhausted: "FAIL_EVENT",
        scope: "WHOLE_NODE",
      }),
    );
  });

  it("creates a backend rework policy from editor configuration", () => {
    const [savedEdge] = toBackendEdges("version-1", [
      {
        id: "edge-revision-review",
        source: "node-approval",
        target: "node-review",
        sourceHandle: "REVISION_REQUESTED",
        data: {
          transitionType: "REWORK",
          reworkConfig: {
            targetStepId: "node-review",
            maxIterations: 3,
            exhaustionBehavior: "ROUTE_ESCALATION",
            exhaustionPort: "REJECTED",
            rollbackStrategy: "KEEP_CURRENT",
            multiInstanceScope: "WHOLE_NODE",
          },
        },
      },
    ]);

    expect(savedEdge.configJson).toEqual(
      expect.objectContaining({
        reworkPolicy: {
          maxIterations: 3,
          onExhausted: "ROUTE_ESCALATION",
          scope: "WHOLE_NODE",
          allowParallelScopeReset: false,
          exhaustionPort: "REJECTED",
        },
        rollbackStrategy: "KEEP_CURRENT",
      }),
    );
  });
});
