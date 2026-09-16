import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { TicketDetailView } from "./components/ticket-detail-view";
import { EventDetailView } from "./components/event-detail-view";
import { AuthSessionProvider } from "@/features/auth";
import { PRESET_ACTORS } from "@/features/auth/types";
import type { EventMonitoringView, TicketAggregate } from "./types";
import { getTicketDisplayName } from "./ticket-display";

const MOCK_TICKET_AGGREGATE: TicketAggregate = {
  ticket: {
    id: "ticket-12345678",
    requestTypeId: "req-1",
    creatorId: "user-1",
    status: "IN_PROGRESS",
    dataJson: {
      equipmentType: "MacBook Pro 16",
      budget: 3500,
      attachmentUrl: "file:///docs/quote.pdf",
    },
    dataRevision: 2,
    currentRevisionId: "rev-2",
    lockVersion: 4,
    createdAt: new Date("2026-01-01T10:00:00Z").toISOString(),
    updatedAt: new Date("2026-01-01T11:00:00Z").toISOString(),
  },
  currentEventId: "event-87654321",
  revisions: [
    {
      id: "rev-1",
      ticketId: "ticket-12345678",
      revisionNo: 1,
      dataSnapshotJson: { equipmentType: "MacBook Air", budget: 1500 },
      sourceSchemaVersion: "1.0",
      schemaChecksum: "hash-1",
      submittedBy: "user-1",
      submittedAt: new Date("2026-01-01T10:00:00Z").toISOString(),
      changeReason: "Initial request",
    },
    {
      id: "rev-2",
      ticketId: "ticket-12345678",
      revisionNo: 2,
      dataSnapshotJson: { equipmentType: "MacBook Pro 16", budget: 3500 },
      sourceSchemaVersion: "1.1",
      schemaChecksum: "hash-2",
      submittedBy: "user-1",
      submittedAt: new Date("2026-01-01T11:00:00Z").toISOString(),
      changeReason: "Upgraded spec for software engineering",
    },
  ],
  subjects: [
    {
      id: "sub-1",
      ticketId: "ticket-12345678",
      subjectType: "USER",
      subjectRefId: "user-1",
      roleKey: "REQUESTER",
      sourceField: "creator",
      createdAt: new Date("2026-01-01T10:00:00Z").toISOString(),
    },
  ],
};

const MOCK_EVENT_VIEW: EventMonitoringView = {
  eventId: "event-87654321",
  ticketId: "ticket-12345678",
  workflowVersion: {
    id: "wf-ver-1",
    definitionId: "wf-def-1",
    versionNo: 2,
    status: "PUBLISHED",
    checksum: "chk-123",
  },
  graph: { nodes: [], edges: [] },
  status: "RUNNING",
  outcome: "PENDING_APPROVAL",
  nodeExecutions: [
    {
      id: "node-1",
      nodeDefinitionId: "node-def-start",
      nodeName: "Start Node",
      status: "COMPLETED",
      outcomePort: "DEFAULT",
      iteration: 1,
      createdAt: new Date("2026-01-01T10:00:00Z").toISOString(),
    },
    {
      id: "node-2",
      nodeDefinitionId: "node-def-subwf",
      nodeName: "Security Audit SubWorkflow",
      status: "RUNNING",
      childEventId: "child-event-9999",
      iteration: 1,
      createdAt: new Date("2026-01-01T10:05:00Z").toISOString(),
    },
  ],
  tasks: [
    {
      id: "task-100",
      nodeExecutionId: "node-2",
      status: "READY",
      assigneeName: "Charlie Security",
      dueAt: new Date("2026-01-05T00:00:00Z").toISOString(),
      createdAt: new Date("2026-01-01T10:05:00Z").toISOString(),
    },
  ],
  participantSnapshots: [],
  assignmentHistory: [],
  routingDecisions: [],
  timeline: [
    {
      at: new Date("2026-01-01T10:00:00Z").toISOString(),
      type: "NODE",
      id: "node-1",
      state: "COMPLETED",
    },
    {
      at: new Date("2026-01-01T10:05:00Z").toISOString(),
      type: "NODE",
      id: "node-2",
      state: "RUNNING",
    },
  ],
  maskedContext: {
    budget: 3500,
    securityLevel: "HIGH",
  },
};

describe("TicketDetailView Component", () => {
  it("renders only user-facing ticket data, status, and event link", () => {
    render(<TicketDetailView aggregate={MOCK_TICKET_AGGREGATE} />);

    expect(screen.getByTestId("ticket-status-badge")).toHaveTextContent(
      "IN_PROGRESS",
    );
    expect(getTicketDisplayName(MOCK_TICKET_AGGREGATE.ticket)).toBe(
      "Yêu cầu cấp thiết bị",
    );
    expect(screen.getByText("MacBook Pro 16")).toBeInTheDocument();
    expect(screen.getByText("3500")).toBeInTheDocument();

    expect(screen.getByText("Tệp đính kèm")).toBeInTheDocument();

    // Technical revision and subject data are not shown to requesters.
    expect(screen.queryByTestId("tab-history")).not.toBeInTheDocument();
    expect(screen.queryByTestId("tab-subjects")).not.toBeInTheDocument();

    // Verify link to the user-facing progress view is present
    const eventLink = screen.getByTestId("link-to-event");
    expect(eventLink).toHaveAttribute("href", "/events/event-87654321");
  });
});

describe("EventDetailView Component", () => {
  it("renders event progress, node occurrences, and link to child subworkflow", () => {
    render(
      <AuthSessionProvider
        initialSession={{ status: "authenticated", actor: PRESET_ACTORS[0] }} // Alice User
      >
        <EventDetailView event={MOCK_EVENT_VIEW} />
      </AuthSessionProvider>,
    );

    expect(screen.getByTestId("event-status-badge")).toHaveTextContent(
      "RUNNING",
    );
    expect(screen.getByText("Start Node")).toBeInTheDocument();
    expect(screen.getByText("Security Audit SubWorkflow")).toBeInTheDocument();

    // Verify link to child subworkflow
    const childLink = screen.getByTestId("link-child-event-child-event-9999");
    expect(childLink).toHaveAttribute("href", "/events/child-event-9999");

    // Ordinary user does NOT see privileged technical execution context
    expect(
      screen.getByTestId("unprivileged-hidden-notice"),
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId("privileged-graph-section"),
    ).not.toBeInTheDocument();
  });

  it("shows business-friendly timeline labels instead of engine event codes", () => {
    const eventWithInternalTimelineCodes: EventMonitoringView = {
      ...MOCK_EVENT_VIEW,
      timeline: [
        {
          at: new Date("2026-01-01T10:00:00Z").toISOString(),
          type: "NODE",
          id: "node-1",
          state: "COMPLETED",
        },
        {
          at: new Date("2026-01-01T10:01:00Z").toISOString(),
          type: "ROUTING",
          id: "route-1",
          state: "SINGLE_BY_PORT",
        },
        {
          at: new Date("2026-01-01T10:02:00Z").toISOString(),
          type: "PARTICIPANT",
          id: "participant-1",
          state: "RESOLVED",
        },
        {
          at: new Date("2026-01-01T10:03:00Z").toISOString(),
          type: "NODE",
          id: "node-3",
          state: "WAITING",
        },
        {
          at: new Date("2026-01-01T10:04:00Z").toISOString(),
          type: "TASK",
          id: "task-1",
          state: "READY",
        },
      ],
    };

    render(
      <AuthSessionProvider
        initialSession={{ status: "authenticated", actor: PRESET_ACTORS[0] }}
      >
        <EventDetailView event={eventWithInternalTimelineCodes} />
      </AuthSessionProvider>,
    );

    expect(screen.getByTestId("timeline-entry-0")).toHaveTextContent(
      "Hoàn tất bước xử lý",
    );
    expect(screen.getByTestId("timeline-entry-1")).toHaveTextContent(
      "Chuyển sang nhánh xử lý",
    );
    expect(screen.getByTestId("timeline-entry-2")).toHaveTextContent(
      "Đã xác định người xử lý",
    );
    expect(screen.getByTestId("timeline-entry-3")).toHaveTextContent(
      "Bước đang chờ xử lý",
    );
    expect(screen.getByTestId("timeline-entry-4")).toHaveTextContent(
      "Công việc sẵn sàng",
    );
    expect(screen.getByTestId("timeline-section")).not.toHaveTextContent(
      "NODECOMPLETED",
    );
    expect(screen.getByTestId("timeline-section")).not.toHaveTextContent(
      "Mã tham chiếu",
    );
  });

  it("shows privileged execution context to operator / admin users", () => {
    render(
      <AuthSessionProvider
        initialSession={{ status: "authenticated", actor: PRESET_ACTORS[2] }} // Charlie Ops: OPERATOR
      >
        <EventDetailView event={MOCK_EVENT_VIEW} />
      </AuthSessionProvider>,
    );

    expect(screen.getByTestId("privileged-graph-section")).toBeInTheDocument();
    expect(screen.getByTestId("technical-context-json")).toBeInTheDocument();
  });
});
