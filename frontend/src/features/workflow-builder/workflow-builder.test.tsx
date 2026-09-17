import { describe, expect, it, vi } from "vitest";
import { act, render, screen, fireEvent } from "@testing-library/react";
import { WorkflowBuilder } from "./components/workflow-builder";
import type { WorkflowVersionDto } from "./types";
import { ApiRequestError } from "@/shared/api/client";

// Mock React Flow to make it testable in jsdom without WebGL/DOM layout dimensions
vi.mock("@xyflow/react", async () => {
  const actual = await vi.importActual("@xyflow/react");
  return {
    ...actual,
    ReactFlow: ({
      children,
      nodes,
      edges,
      onDrop,
      onDragOver,
      onEdgeMouseEnter,
      onEdgeMouseLeave,
    }: {
      children: React.ReactNode;
      nodes: Array<{ id: string; data: { label: string; key: string } }>;
      edges?: Array<{ id: string; label?: React.ReactNode }>;
      onDrop?: React.DragEventHandler<HTMLDivElement>;
      onDragOver?: React.DragEventHandler<HTMLDivElement>;
      onEdgeMouseEnter?: (
        event: React.MouseEvent,
        edge: { id: string },
      ) => void;
      onEdgeMouseLeave?: (
        event: React.MouseEvent,
        edge: { id: string },
      ) => void;
    }) => (
      <div
        data-testid="mock-react-flow"
        onDrop={onDrop}
        onDragOver={onDragOver}
      >
        {nodes.map((n) => (
          <div key={n.id} data-testid={`node-element-${n.id}`}>
            {n.data.label}
          </div>
        ))}
        {edges?.map((edge) => (
          <button
            key={edge.id}
            type="button"
            data-testid={`edge-element-${edge.id}`}
            onMouseEnter={(event) => onEdgeMouseEnter?.(event, edge)}
            onMouseLeave={(event) => onEdgeMouseLeave?.(event, edge)}
          >
            {edge.label ?? edge.id}
          </button>
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
    expect(screen.queryByTestId("properties-panel")).not.toBeInTheDocument();
    expect(screen.getByTestId("builder-version-status")).toHaveTextContent(
      "DRAFT",
    );
    expect(screen.getByText("Phiên bản #1")).toBeInTheDocument();

    // Verify nodes are rendered on canvas
    expect(screen.getByTestId("node-element-node_start")).toHaveTextContent(
      "Start",
    );
    expect(screen.getByTestId("node-element-node_end")).toHaveTextContent(
      "End",
    );
  });

  it("shows the connection source and destination while hovering an edge", () => {
    render(<WorkflowBuilder initialVersion={TEST_DRAFT_VERSION} />);

    const edge = screen.getByTestId("edge-element-edge_start_end");
    expect(edge).toHaveTextContent("edge_start_end");

    fireEvent.mouseEnter(edge);
    expect(edge).toHaveTextContent("Bắt đầu → Kết thúc");

    fireEvent.mouseLeave(edge);
    expect(edge).toHaveTextContent("edge_start_end");
  });

  it("collapses and reopens the node catalog to give the canvas more space", () => {
    render(<WorkflowBuilder initialVersion={TEST_DRAFT_VERSION} />);

    fireEvent.click(screen.getByTestId("collapse-node-catalog"));
    expect(screen.getByTestId("expand-node-catalog")).toBeInTheDocument();
    expect(
      screen.queryByTestId("collapse-node-catalog"),
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId("expand-node-catalog"));
    expect(screen.getByTestId("collapse-node-catalog")).toBeInTheDocument();
  });

  it("adds a new node from the catalog to the canvas in draft mode", () => {
    render(<WorkflowBuilder initialVersion={TEST_DRAFT_VERSION} />);

    // Click Add on Approval node
    const addApprovalBtn = screen.getByTestId("add-node-approval");
    fireEvent.click(addApprovalBtn);

    // Verify new approval node is added to canvas
    expect(screen.getAllByText("Phê duyệt").length).toBeGreaterThan(0);

    // Verify properties panel is open for newly selected node
    expect(screen.getByTestId("properties-panel")).toBeInTheDocument();
    expect(screen.getByTestId("prop-node-label")).toHaveValue("Phê duyệt");

    fireEvent.click(screen.getByTestId("close-properties-modal"));
    expect(screen.queryByTestId("properties-panel")).not.toBeInTheDocument();
  });

  it("adds a new node when dragged from the catalog onto the canvas", () => {
    render(<WorkflowBuilder initialVersion={TEST_DRAFT_VERSION} />);

    const dataTransfer = {
      effectAllowed: "",
      dropEffect: "",
      setData: vi.fn(),
      getData: vi.fn((format: string) =>
        format === "application/x-workflow-node" ? "REVIEW" : "",
      ),
    };

    fireEvent.dragStart(screen.getByTestId("catalog-node-review"), {
      dataTransfer,
    });
    expect(dataTransfer.setData).toHaveBeenCalledWith(
      "application/x-workflow-node",
      "REVIEW",
    );

    fireEvent.drop(screen.getByTestId("mock-react-flow"), {
      dataTransfer,
      clientX: 700,
      clientY: 300,
    });

    expect(screen.getAllByText("Kiểm tra").length).toBeGreaterThan(0);
    expect(screen.getByTestId("properties-panel")).toBeInTheDocument();
    expect(screen.getByTestId("prop-node-label")).toHaveValue("Kiểm tra");
  });

  it("enforces publish guard: published version is strictly read-only", () => {
    const onCloneAsNewDraft = vi.fn();
    render(
      <WorkflowBuilder
        initialVersion={TEST_PUBLISHED_VERSION}
        onCloneAsNewDraft={onCloneAsNewDraft}
      />,
    );

    expect(screen.getByTestId("builder-version-status")).toHaveTextContent(
      "PUBLISHED",
    );
    expect(screen.getByTestId("read-only-banner")).toBeInTheDocument();

    // Save Draft and Publish buttons MUST be disabled
    expect(screen.getByTestId("toolbar-save-draft-button")).toBeDisabled();
    expect(screen.getByTestId("toolbar-publish-button")).toBeDisabled();

    // Catalog additions MUST be disabled
    const addApprovalBtn = screen.getByTestId("add-node-approval");
    expect(addApprovalBtn).toBeDisabled();
    expect(
      screen.queryByText(
        "Phiên bản đã phát hành chỉ xem. Bấm Tạo bản nháp để tiếp tục chỉnh sửa.",
      ),
    ).not.toBeInTheDocument();

    const cloneButton = screen.getByTestId("toolbar-clone-draft-button");
    expect(cloneButton).toHaveClass("whitespace-nowrap");
    fireEvent.click(cloneButton);
    expect(onCloneAsNewDraft).toHaveBeenCalledWith(TEST_PUBLISHED_VERSION);
  });

  it("keeps a Draft read-only when the viewer lacks definition edit permission", () => {
    render(
      <WorkflowBuilder
        initialVersion={TEST_DRAFT_VERSION}
        readOnly
        publishAllowed={false}
      />,
    );

    expect(screen.getByTestId("read-only-banner")).toBeInTheDocument();
    expect(screen.getByTestId("toolbar-save-draft-button")).toBeDisabled();
    expect(screen.getByTestId("toolbar-publish-button")).toBeDisabled();
    expect(screen.getByTestId("add-node-approval")).toBeDisabled();
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
      screen.getByText("Quy trình chưa có bước Bắt đầu."),
    ).toBeInTheDocument();

    // Clicking the unreachable node issue focuses the node
    const unreachableIssue = screen.getByTestId(
      "validation-issue-warn-unreachable-orphan_end",
    );
    fireEvent.click(unreachableIssue);

    // Node is selected -> Properties panel opens for orphan_end
    expect(screen.getByTestId("properties-panel")).toBeInTheDocument();
    expect(screen.getByTestId("prop-node-label")).toHaveValue("Orphan End");

    // The validation report can be dismissed after reviewing an issue.
    fireEvent.click(screen.getByTestId("close-validation-panel"));
    expect(screen.queryByTestId("validation-panel")).not.toBeInTheDocument();
  });

  it("does not show stale server start/end errors for the current graph", async () => {
    const onValidate = vi.fn().mockResolvedValue([
      {
        id: "server-no-start",
        code: "NO_START",
        severity: "ERROR",
        message: "Exactly one START is required",
      },
      {
        id: "server-no-end",
        code: "NO_END",
        severity: "ERROR",
        message: "At least one END is required",
      },
    ]);

    render(
      <WorkflowBuilder
        initialVersion={TEST_DRAFT_VERSION}
        onValidate={onValidate}
      />,
    );

    await act(async () => {
      fireEvent.click(screen.getByTestId("toolbar-validate-button"));
    });

    expect(onValidate).toHaveBeenCalledTimes(1);
    expect(
      screen.queryByText("Exactly one START is required"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("At least one END is required"),
    ).not.toBeInTheDocument();
  });

  it("validates unsaved canvas changes locally instead of persisted data", async () => {
    const onValidate = vi.fn().mockResolvedValue([]);

    render(
      <WorkflowBuilder
        initialVersion={TEST_DRAFT_VERSION}
        onValidate={onValidate}
      />,
    );

    fireEvent.click(screen.getByTestId("add-node-approval"));

    await act(async () => {
      fireEvent.click(screen.getByTestId("toolbar-validate-button"));
    });

    expect(onValidate).not.toHaveBeenCalled();
    expect(
      screen.getByText("Bước “Phê duyệt” chưa được nối từ bước trước."),
    ).toBeInTheDocument();
  });

  it("shows save API errors instead of creating an unhandled rejection", async () => {
    const onSave = vi.fn().mockRejectedValue(
      new ApiRequestError(422, {
        code: "WORKFLOW_GRAPH_NODE_KEY_REQUIRED",
        message: "Every graph node requires a nodeKey",
      }),
    );

    render(
      <WorkflowBuilder initialVersion={TEST_DRAFT_VERSION} onSave={onSave} />,
    );

    fireEvent.click(screen.getByTestId("toolbar-save-draft-button"));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Every graph node requires a nodeKey (WORKFLOW_GRAPH_NODE_KEY_REQUIRED)",
    );
    expect(screen.queryByTestId("save-draft-toast")).not.toBeInTheDocument();
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it("notifies the host after a successful draft save", async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    const onSaveSuccess = vi.fn();

    render(
      <WorkflowBuilder
        initialVersion={TEST_DRAFT_VERSION}
        onSave={onSave}
        onSaveSuccess={onSaveSuccess}
      />,
    );

    await act(async () => {
      fireEvent.click(screen.getByTestId("toolbar-save-draft-button"));
    });

    expect(onSave).toHaveBeenCalledTimes(1);
    expect(onSaveSuccess).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("save-draft-toast")).toHaveTextContent(
      "Đã lưu bản nháp thành công.",
    );
  });

  it("notifies the host with the published version after successful publication", async () => {
    const onPublish = vi.fn().mockResolvedValue("published-version-1");
    const onPublishSuccess = vi.fn();

    render(
      <WorkflowBuilder
        initialVersion={TEST_DRAFT_VERSION}
        onPublish={onPublish}
        onPublishSuccess={onPublishSuccess}
      />,
    );

    fireEvent.click(screen.getByTestId("toolbar-publish-button"));
    await act(async () => {
      fireEvent.click(screen.getByTestId("confirm-publish-btn"));
    });

    expect(onPublish).toHaveBeenCalledWith(TEST_DRAFT_VERSION.id);
    expect(onPublishSuccess).toHaveBeenCalledWith("published-version-1");
    expect(screen.getByTestId("publish-success-message")).toBeInTheDocument();
  });
});
