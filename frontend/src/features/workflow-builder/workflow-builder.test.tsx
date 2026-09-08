import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { WorkflowBuilder } from "./components/workflow-builder";
import type { WorkflowVersionDto } from "./types";

// Mock React Flow to make it testable in jsdom without WebGL/DOM layout dimensions
vi.mock("@xyflow/react", async () => {
  const actual = await vi.importActual("@xyflow/react");
  return {
    ...actual,
    ReactFlow: ({ children, nodes }: { children: React.ReactNode; nodes: Array<{ id: string; data: { label: string; key: string } }> }) => (
      <div data-testid="mock-react-flow">
        {nodes.map((n) => (
          <div key={n.id} data-testid={`node-element-${n.id}`}>
            {n.data.label}
          </div>
        ))}
        {children}
      </div>
    ),
    Background: () => <div data-testid="mock-background" />,
    Controls: () => <div data-testid="mock-controls" />,
  };
});

const TEST_DRAFT_VERSION: WorkflowVersionDto = {
  id: "ver-draft-101",
  definitionId: "def-101",
  versionNo: 1,
  status: "DRAFT",
  revision: 1,
  nodes: [
    {
      id: "node_start",
      type: "workflowNode",
      position: { x: 50, y: 100 },
      data: {
        key: "start",
        label: "Start",
        nodeType: "START",
        outputPorts: ["DEFAULT"],
        config: {},
      },
    },
    {
      id: "node_end",
      type: "workflowNode",
      position: { x: 500, y: 100 },
      data: {
        key: "end",
        label: "End",
        nodeType: "END",
        outputPorts: [],
        config: {},
      },
    },
  ],
  edges: [
    {
      id: "edge_start_end",
      source: "node_start",
      target: "node_end",
      sourceHandle: "DEFAULT",
    },
  ],
};

const TEST_PUBLISHED_VERSION: WorkflowVersionDto = {
  ...TEST_DRAFT_VERSION,
  id: "ver-pub-201",
  versionNo: 2,
  status: "PUBLISHED",
};

describe("WorkflowBuilder Component", () => {
  it("loads nodes and edges on canvas with toolbar version details", () => {
    render(<WorkflowBuilder initialVersion={TEST_DRAFT_VERSION} />);

    expect(screen.getByTestId("workflow-builder-shell")).toBeInTheDocument();
    expect(screen.getByTestId("node-catalog-panel")).toBeInTheDocument();
    expect(screen.getByTestId("builder-version-status")).toHaveTextContent("DRAFT");
    expect(screen.getByText("Version #1")).toBeInTheDocument();

    // Verify nodes are rendered on canvas
    expect(screen.getByTestId("node-element-node_start")).toHaveTextContent("Start");
    expect(screen.getByTestId("node-element-node_end")).toHaveTextContent("End");
  });

  it("adds a new node from the catalog to the canvas in draft mode", () => {
    render(<WorkflowBuilder initialVersion={TEST_DRAFT_VERSION} />);

    // Click Add on Approval node
    const addApprovalBtn = screen.getByTestId("add-node-approval");
    fireEvent.click(addApprovalBtn);

    // Verify new approval node is added to canvas
    expect(screen.getByText("Approval Task")).toBeInTheDocument();

    // Verify properties panel is open for newly selected node
    expect(screen.getByTestId("properties-panel")).toBeInTheDocument();
    expect(screen.getByTestId("prop-node-label")).toHaveValue("Approval Task");
  });

  it("enforces publish guard: published version is strictly read-only", () => {
    render(<WorkflowBuilder initialVersion={TEST_PUBLISHED_VERSION} />);

    expect(screen.getByTestId("builder-version-status")).toHaveTextContent("PUBLISHED");
    expect(screen.getByTestId("read-only-banner")).toBeInTheDocument();

    // Save Draft and Publish buttons MUST be disabled
    expect(screen.getByTestId("toolbar-save-draft-button")).toBeDisabled();
    expect(screen.getByTestId("toolbar-publish-button")).toBeDisabled();

    // Catalog additions MUST be disabled
    const addApprovalBtn = screen.getByTestId("add-node-approval");
    expect(addApprovalBtn).toBeDisabled();
  });

  it("validates graph rules and focuses node when clicking validation issue", () => {
    // Version with invalid graph: END node without incoming connection, missing START
    const INVALID_VERSION: WorkflowVersionDto = {
      id: "ver-invalid-1",
      definitionId: "def-1",
      versionNo: 1,
      status: "DRAFT",
      revision: 1,
      nodes: [
        {
          id: "orphan_end",
          type: "workflowNode",
          position: { x: 100, y: 100 },
          data: {
            key: "orphan_end",
            label: "Orphan End",
            nodeType: "END",
            outputPorts: [],
            config: {},
          },
        },
      ],
      edges: [],
    };

    render(<WorkflowBuilder initialVersion={INVALID_VERSION} />);

    // Click Validate in toolbar
    const validateBtn = screen.getByTestId("toolbar-validate-button");
    fireEvent.click(validateBtn);

    // Validation panel opens
    expect(screen.getByTestId("validation-panel")).toBeInTheDocument();
    expect(
      screen.getByText("Workflow must contain exactly one Start node."),
    ).toBeInTheDocument();

    // Clicking the unreachable node issue focuses the node
    const unreachableIssue = screen.getByTestId("validation-issue-warn-unreachable-orphan_end");
    fireEvent.click(unreachableIssue);

    // Node is selected -> Properties panel opens for orphan_end
    expect(screen.getByTestId("properties-panel")).toBeInTheDocument();
    expect(screen.getByTestId("prop-node-label")).toHaveValue("Orphan End");
  });
});
