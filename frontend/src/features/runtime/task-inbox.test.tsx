import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { TaskInbox } from "./components/task-inbox";
import type { TaskItem } from "./types";
import * as api from "./api";
import { ApiRequestError } from "@/shared/api/client";

const MOCK_TASKS: TaskItem[] = [
  {
    id: "task-1",
    nodeExecutionId: "node-exec-1",
    title: "Approve Hardware Purchase",
    description: "MacBook Pro purchase for new engineer",
    status: "READY",
    priority: 10,
    lockVersion: 3,
    createdAt: new Date().toISOString(),
  },
  {
    id: "task-2",
    nodeExecutionId: "node-exec-2",
    title: "Security Review",
    description: "Validate SSH access keys",
    status: "CLAIMED",
    priority: 20,
    lockVersion: 5,
    createdAt: new Date().toISOString(),
  },
  {
    id: "task-3",
    nodeExecutionId: "node-exec-3",
    title: "Finance Verification",
    description: "Verify cost center allocation",
    status: "IN_PROGRESS",
    priority: 30,
    lockVersion: 2,
    createdAt: new Date().toISOString(),
  },
];

describe("TaskInbox Component", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("filters tasks by status tabs (READY, CLAIMED, IN_PROGRESS)", () => {
    render(<TaskInbox initialTasks={MOCK_TASKS} />);

    expect(screen.getByText("Approve Hardware Purchase")).toBeInTheDocument();
    expect(screen.getByText("Security Review")).toBeInTheDocument();
    expect(screen.getByText("Finance Verification")).toBeInTheDocument();

    // Click READY filter
    fireEvent.click(screen.getByTestId("filter-ready"));
    expect(screen.getByText("Approve Hardware Purchase")).toBeInTheDocument();
    expect(screen.queryByText("Security Review")).not.toBeInTheDocument();
    expect(screen.queryByText("Finance Verification")).not.toBeInTheDocument();

    // Click CLAIMED filter
    fireEvent.click(screen.getByTestId("filter-claimed"));
    expect(screen.queryByText("Approve Hardware Purchase")).not.toBeInTheDocument();
    expect(screen.getByText("Security Review")).toBeInTheDocument();
  });

  it("sends commandId and expectedVersion when executing task action command", async () => {
    const executeSpy = vi.spyOn(api, "executeTaskAction").mockResolvedValue({
      ...MOCK_TASKS[0],
      status: "CLAIMED",
      lockVersion: 4,
    });

    render(<TaskInbox initialTasks={MOCK_TASKS} />);

    // Click Claim on task-1 (lockVersion = 3)
    const claimBtn = screen.getByTestId("action-claim-task-1");
    fireEvent.click(claimBtn);

    await waitFor(() => {
      expect(executeSpy).toHaveBeenCalledWith(
        "task-1",
        expect.objectContaining({
          action: "claim",
          expectedVersion: 3,
          commandId: expect.any(String),
        }),
      );
    });

    // Verify task status updated to CLAIMED after backend confirmation
    await waitFor(() => {
      expect(screen.getByTestId("task-status-task-1")).toHaveTextContent("CLAIMED");
    });
  });

  it("handles 409 CONFLICT gracefully, prompts refresh, and preserves user input", async () => {
    // Mock 409 Conflict
    vi.spyOn(api, "executeTaskAction").mockRejectedValue(
      new ApiRequestError(409, {
        code: "CONFLICT",
        message: "Stale version: task has been updated concurrently",
      }),
    );

    render(<TaskInbox initialTasks={MOCK_TASKS} />);

    // Open Reject dialog on task-2
    const rejectBtn = screen.getByTestId("action-reject-task-2");
    fireEvent.click(rejectBtn);

    expect(screen.getByTestId("task-action-modal")).toBeInTheDocument();

    // Fill comment
    const commentInput = screen.getByTestId("action-comment-input");
    fireEvent.change(commentInput, {
      target: { value: "Detailed rejection reason that took minutes to write" },
    });

    // Confirm action
    const confirmBtn = screen.getByTestId("confirm-dialog-button");
    fireEvent.click(confirmBtn);

    // Verify 409 conflict banner is shown
    await waitFor(() => {
      expect(screen.getByTestId("conflict-error-banner")).toBeInTheDocument();
      expect(
        screen.getByText(/Conflict detected: This task has been updated concurrently/i),
      ).toBeInTheDocument();
    });

    // Verify user's filled comment is PRESERVED in the input field!
    expect(screen.getByTestId("action-comment-input")).toHaveValue(
      "Detailed rejection reason that took minutes to write",
    );

    // Verify task list has NOT mutated locally
    expect(screen.getByTestId("task-status-task-2")).toHaveTextContent("CLAIMED");
  });
});
