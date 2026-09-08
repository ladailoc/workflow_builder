import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { ValidationPanel } from "./components/validation-panel";
import { SimulationModal } from "./components/simulation-modal";
import { PublishModal } from "./components/publish-modal";
import { VersionHistoryDrawer } from "./components/version-history-drawer";
import { VersionDiffModal } from "./components/version-diff-modal";
import type { BuilderEdge, BuilderNode, WorkflowVersionDto } from "./types";

describe("Prompt 57: Validate / Simulate / Diff / Publish & Rollback Frontend", () => {
  const validNodes: BuilderNode[] = [
    {
      id: "node_start",
      type: "workflowNode",
      position: { x: 0, y: 0 },
      data: {
        key: "start_1",
        label: "Start",
        nodeType: "START",
        outputPorts: ["DEFAULT"],
        config: {},
      },
    },
    {
      id: "node_approval",
      type: "workflowNode",
      position: { x: 150, y: 150 },
      data: {
        key: "appr_1",
        label: "Manager Approval",
        nodeType: "APPROVAL",
        outputPorts: ["APPROVED", "REJECTED"],
        config: {
          participant: { type: "MANAGER_OF", depth: 1 },
          multiInstance: { mode: "PARALLEL" },
        },
      },
    },
    {
      id: "node_end",
      type: "workflowNode",
      position: { x: 300, y: 300 },
      data: {
        key: "end_1",
        label: "End",
        nodeType: "END",
        outputPorts: [],
        config: {},
      },
    },
  ];

  const validEdges: BuilderEdge[] = [
    {
      id: "edge_1",
      source: "node_start",
      target: "node_approval",
      sourceHandle: "DEFAULT",
    },
    {
      id: "edge_2",
      source: "node_approval",
      target: "node_end",
      sourceHandle: "APPROVED",
      label: "Approved > 5000",
      data: {
        condition: "payload.totalAmount > 5000",
      },
    },
  ];

  const currentDraftVersion: WorkflowVersionDto = {
    id: "ver-draft-3",
    definitionId: "wf-def-1",
    versionNo: 3,
    status: "DRAFT",
    revision: 1,
    nodes: validNodes,
    edges: validEdges,
  };

  const historicalV2: WorkflowVersionDto = {
    id: "ver-pub-2",
    definitionId: "wf-def-1",
    versionNo: 2,
    status: "PUBLISHED",
    revision: 4,
    nodes: [validNodes[0], validNodes[2]], // missing approval node
    edges: [
      {
        id: "edge_direct",
        source: "node_start",
        target: "node_end",
      },
    ],
  };

  describe("Validation Issue Focus & Codes", () => {
    it("renders ValidationReport summary with stable issue codes and invokes focus on click", () => {
      const focusSpy = vi.fn();
      const closeSpy = vi.fn();

      render(
        <ValidationPanel
          isOpen={true}
          issues={[
            {
              id: "err-1",
              code: "ERR_NO_START",
              severity: "ERROR",
              message: "Workflow must contain exactly one Start node.",
              nodeId: "node_start",
              field: "nodeType",
            },
            {
              id: "warn-1",
              code: "WARN_UNREACHABLE_NODE",
              severity: "WARNING",
              message: "Node 'Manager Approval' is unreachable.",
              nodeId: "node_approval",
            },
          ]}
          onClose={closeSpy}
          onSelectIssue={focusSpy}
        />,
      );

      expect(screen.getByTestId("validation-panel")).toBeInTheDocument();
      expect(screen.getByTestId("validation-error-count")).toHaveTextContent("1 Error");
      expect(screen.getByTestId("validation-warning-count")).toHaveTextContent("1 Warning");

      // Verify stable issue codes
      const codes = screen.getAllByTestId("validation-issue-code");
      expect(codes[0]).toHaveTextContent("ERR_NO_START");
      expect(codes[1]).toHaveTextContent("WARN_UNREACHABLE_NODE");

      // Click focus button
      fireEvent.click(screen.getByTestId("validation-issue-err-1"));
      expect(focusSpy).toHaveBeenCalledWith("node_start", "nodeType");
    });
  });

  describe("Simulation Preview Rendering", () => {
    it("runs side-effect-free in-memory simulation showing participants, branch selection, and fan-out", () => {
      const closeSpy = vi.fn();
      render(
        <SimulationModal
          isOpen={true}
          nodes={validNodes}
          edges={validEdges}
          onClose={closeSpy}
        />,
      );

      expect(screen.getByTestId("simulation-modal")).toBeInTheDocument();
      expect(screen.getByText("Side-Effect Free")).toBeInTheDocument();

      // Run simulation
      fireEvent.click(screen.getByTestId("btn-run-simulation"));

      // Verify simulated steps rendered
      expect(screen.getByTestId("simulation-step-0")).toHaveTextContent("Start");
      expect(screen.getByTestId("simulation-step-1")).toHaveTextContent("Manager Approval");

      // Verify participant resolved preview
      expect(screen.getByTestId("sim-participant-preview")).toHaveTextContent("Bob Director");

      // Verify multi-instance fan-out count (3 items in mock context)
      expect(screen.getByTestId("sim-fanout-count")).toHaveTextContent("Spawns 3 parallel tasks");

      // Test step backward and forward
      fireEvent.click(screen.getByTestId("btn-step-backward"));
      fireEvent.click(screen.getByTestId("btn-step-forward"));
    });
  });

  describe("Publish Revalidation Enforcement", () => {
    it("blocks publication when graph contains validation errors", () => {
      const confirmSpy = vi.fn();
      const closeSpy = vi.fn();

      // Invalid graph: missing End node
      const invalidNodes = [validNodes[0], validNodes[1]];

      render(
        <PublishModal
          isOpen={true}
          version={currentDraftVersion}
          nodes={invalidNodes}
          edges={validEdges}
          onConfirmPublish={confirmSpy}
          onClose={closeSpy}
        />,
      );

      expect(screen.getByTestId("publish-blocked-alert")).toBeInTheDocument();
      expect(screen.getByText(/Publication Blocked/i)).toBeInTheDocument();

      const publishBtn = screen.getByTestId("confirm-publish-btn");
      expect(publishBtn).toBeDisabled();

      fireEvent.click(publishBtn);
      expect(confirmSpy).not.toHaveBeenCalled();
    });

    it("allows publication when graph passes validation with 0 errors", async () => {
      const confirmSpy = vi.fn().mockResolvedValue(undefined);
      const closeSpy = vi.fn();

      render(
        <PublishModal
          isOpen={true}
          version={currentDraftVersion}
          nodes={validNodes}
          edges={validEdges}
          onConfirmPublish={confirmSpy}
          onClose={closeSpy}
        />,
      );

      expect(screen.getByTestId("publish-validation-clean")).toBeInTheDocument();
      const publishBtn = screen.getByTestId("confirm-publish-btn");
      expect(publishBtn).not.toBeDisabled();

      await act(async () => {
        fireEvent.click(publishBtn);
      });
      expect(confirmSpy).toHaveBeenCalledTimes(1);
    });
  });

  describe("Rollback UX (Audit-Safe Cloning)", () => {
    it("creates a new draft revision when rolling back from a historical release", () => {
      const cloneSpy = vi.fn();
      const closeSpy = vi.fn();

      render(
        <VersionHistoryDrawer
          isOpen={true}
          currentVersion={currentDraftVersion}
          versions={[currentDraftVersion, historicalV2]}
          onCloneAsNewDraft={cloneSpy}
          onClose={closeSpy}
        />,
      );

      expect(screen.getByTestId("version-history-drawer")).toBeInTheDocument();
      expect(screen.getByTestId("rollback-policy-callout")).toHaveTextContent("Audit Safe Rollback Model");

      // Verify clone button for historical version #2
      const cloneBtn = screen.getByTestId(`clone-version-btn-${historicalV2.id}`);
      expect(cloneBtn).toBeInTheDocument();

      // Trigger rollback clone
      fireEvent.click(cloneBtn);
      expect(cloneSpy).toHaveBeenCalledWith(historicalV2);
    });
  });

  describe("Semantic Version Diff", () => {
    it("computes and displays added, removed, and modified components between versions", () => {
      const closeSpy = vi.fn();
      render(
        <VersionDiffModal
          isOpen={true}
          currentVersion={currentDraftVersion}
          historicalVersions={[historicalV2]}
          onClose={closeSpy}
        />,
      );

      expect(screen.getByTestId("version-diff-modal")).toBeInTheDocument();
      // Draft has 3 nodes, V2 had 2 nodes -> 1 added node (Manager Approval)
      expect(screen.getByTestId("diff-nodes-added-count")).toHaveTextContent("+1");
      expect(screen.getByTestId("diff-section-added-nodes")).toHaveTextContent("Manager Approval");
    });
  });
});
