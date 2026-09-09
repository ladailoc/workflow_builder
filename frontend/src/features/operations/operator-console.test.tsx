import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { OperatorConsole } from "./components/operator-console";
import * as api from "./api";

vi.mock("./api");

const failure = {
  category: "INTEGRATION_EXECUTION" as const,
  id: "integration-1",
  lockVersion: 3,
  kind: "ERP/CREATE_PO",
  aggregateType: "EVENT",
  aggregateId: "event-1",
  eventId: "event-1",
  eventVersion: 9,
  status: "MANUAL_RECONCILIATION",
  attempts: 3,
  maxAttempts: 3,
  occurredAt: "2026-09-09T00:00:00Z",
  error: { token: "***", code: "LOST_RESPONSE" },
};

describe("OperatorConsole", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.fetchOperationalFailures).mockResolvedValue([failure]);
    vi.mocked(api.executeOperationalCommand).mockResolvedValue({
      executionId: "command-1",
      resultJson: { status: "RESOLVED" },
      resultMetadataJson: {},
      replayed: false,
    });
    vi.mocked(api.fetchOperationalEvent).mockResolvedValue({
      eventId: "event-1",
      ticketId: "ticket-1",
      workflowVersion: {
        id: "version-7",
        definitionId: "definition-1",
        versionNo: 7,
        status: "PUBLISHED",
        checksum: "checksum-7",
      },
      graph: {
        nodes: [
          {
            id: "node-def-1",
            key: "erp",
            type: "SYSTEM_ACTION",
            name: "ERP",
            position: {},
          },
        ],
        edges: [],
      },
      status: "WAITING",
      nodeExecutions: [
        {
          id: "node-execution-1",
          nodeDefinitionId: "node-def-1",
          status: "WAITING",
          cycleId: "cycle-1",
          iteration: 2,
          path: "parallel/finance",
          item: "vendor-4",
          createdAt: "2026-09-09T00:00:00Z",
        },
      ],
      tasks: [],
      participantSnapshots: [],
      assignmentHistory: [],
      routingDecisions: [],
      timeline: [
        {
          at: "2026-09-09T00:00:00Z",
          type: "NODE",
          id: "node-execution-1",
          state: "WAITING",
        },
      ],
      maskedContext: { credentials: "***" },
    });
  });

  it("requires a reason and exposes only sanitized failure details", async () => {
    render(<OperatorConsole />);
    expect(await screen.findByText("ERP/CREATE_PO")).toBeInTheDocument();
    expect(screen.getByTestId("sanitized-error")).toHaveTextContent("***");

    fireEvent.click(screen.getByRole("button", { name: "Resolve manually" }));
    expect(screen.getByRole("status")).toHaveTextContent("reason is required");
    expect(api.executeOperationalCommand).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("Override reason"), {
      target: { value: "ERP confirmed PO creation" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Resolve manually" }));
    await waitFor(() =>
      expect(api.executeOperationalCommand).toHaveBeenCalledWith(
        "/api/v1/integrations/integration-1/resolve",
        3,
        expect.objectContaining({
          reason: "ERP confirmed PO creation",
          outcomePort: "SUCCESS",
        }),
      ),
    );
  });

  it("renders the exact version, scoped occurrence, timeline, and masked context", async () => {
    render(<OperatorConsole />);
    await screen.findByText("ERP/CREATE_PO");
    fireEvent.click(screen.getByRole("button", { name: "Inspect timeline" }));

    expect(
      await screen.findByTestId("event-technical-inspector"),
    ).toHaveTextContent("Version #7");
    expect(screen.getByTestId("node-occurrence")).toHaveTextContent(
      "cycle cycle-1",
    );
    expect(screen.getByTestId("node-occurrence")).toHaveTextContent(
      "path parallel/finance",
    );
    expect(screen.getByTestId("node-occurrence")).toHaveTextContent(
      "item vendor-4",
    );
    expect(screen.getByTestId("masked-context")).toHaveTextContent("***");
  });
});
