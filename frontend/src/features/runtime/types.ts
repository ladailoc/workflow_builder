export interface TicketView {
  id: string;
  requestTypeId: string;
  creatorId: string;
  status:
    | "DRAFT"
    | "SUBMITTED"
    | "IN_PROGRESS"
    | "COMPLETED"
    | "CANCELLED"
    | "REJECTED";
  dataJson: Record<string, unknown>;
  dataRevision: number;
  currentRevisionId: string | null;
  lockVersion: number;
  createdAt: string;
  updatedAt: string;
  submittedAt?: string | null;
  completedAt?: string | null;
}

export interface RevisionView {
  id: string;
  ticketId: string;
  revisionNo: number;
  dataSnapshotJson: Record<string, unknown>;
  sourceSchemaVersion: string;
  schemaChecksum: string;
  submittedBy: string;
  submittedAt: string;
  changeReason?: string | null;
}

export interface SubjectView {
  id: string;
  ticketId: string;
  subjectType: string;
  subjectRefId: string;
  roleKey: string;
  sourceField: string;
  createdAt: string;
}

export interface TicketAggregate {
  ticket: TicketView;
  revisions: RevisionView[];
  subjects: SubjectView[];
  currentEventId?: string | null;
}

export interface NodeOccurrence {
  id: string;
  nodeDefinitionId: string;
  nodeName?: string;
  status: string;
  outcomePort?: string | null;
  cycleId?: string | null;
  iteration: number;
  path?: string | null;
  item?: string | null;
  splitScopeId?: string | null;
  joinScopeId?: string | null;
  childEventId?: string | null;
  createdAt: string;
  endedAt?: string | null;
}

export interface TaskView {
  id: string;
  nodeExecutionId: string;
  itemExecutionId?: string | null;
  status:
    "READY" | "CLAIMED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED" | "EXPIRED";
  outcome?: string | null;
  assigneeId?: string | null;
  assigneeName?: string | null;
  dueAt?: string | null;
  createdAt: string;
  completedAt?: string | null;
}

export interface ParticipantView {
  id: string;
  nodeExecutionId: string;
  itemExecutionId?: string | null;
  resolverType: string;
  subjectType: string;
  subjectRefId: string;
  resolvedUserId: string;
  resolvedAt: string;
}

export interface AssignmentView {
  id: string;
  taskId: string;
  action: string;
  fromUserId?: string | null;
  toUserId?: string | null;
  actorId: string;
  reason?: string | null;
  at: string;
}

export interface RouteView {
  id: string;
  sourceNodeExecutionId: string;
  outcomePort: string;
  routingMode: string;
  selectedEdgeIds?: string[];
  decidedAt: string;
}

export interface TimelineEntry {
  at: string;
  type: "NODE" | "TASK" | "PARTICIPANT" | "ROUTING";
  id: string;
  state: string;
}

export interface EventMonitoringView {
  eventId: string;
  ticketId: string;
  workflowVersion: {
    id: string;
    definitionId: string;
    versionNo: number;
    status: string;
    checksum: string;
  };
  graph: {
    nodes: Array<{
      id: string;
      key: string;
      type: string;
      name?: string | null;
      position?: Record<string, unknown> | null;
    }>;
    edges: Array<{
      id: string;
      sourceNodeId: string;
      sourcePort: string;
      targetNodeId: string;
      label?: string | null;
      transitionType: string;
    }>;
  };
  status: string;
  outcome?: string | null;
  nodeExecutions: NodeOccurrence[];
  tasks: TaskView[];
  participantSnapshots: ParticipantView[];
  assignmentHistory: AssignmentView[];
  routingDecisions: RouteView[];
  timeline: TimelineEntry[];
  maskedContext: Record<string, unknown>;
}

export interface TaskItem {
  id: string;
  nodeExecutionId: string;
  ticketId?: string;
  eventId?: string;
  title: string;
  description?: string | null;
  status:
    "READY" | "CLAIMED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED" | "EXPIRED";
  outcome?: string | null;
  priority: number;
  assigneeId?: string | null;
  dueAt?: string | null;
  createdAt: string;
  completedAt?: string | null;
  lockVersion: number;
  formSchemaJson?: Record<string, unknown> | null;
  inputSnapshotJson?: Record<string, unknown>;
}

export interface TaskActionCommand {
  action:
    | "claim"
    | "complete"
    | "approve"
    | "reject"
    | "request-revision"
    | "reassign";
  commandId: string;
  expectedVersion: number;
  comment?: string;
  targetUserId?: string;
  formData?: Record<string, unknown>;
  requestedFields?: Array<{
    key: string;
    label: string;
    type: string;
    required: boolean;
  }>;
}
