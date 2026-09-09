export interface OperationalFailure {
  category:
    | "WORKFLOW_JOB"
    | "OUTBOX_EVENT"
    | "NOTIFICATION_DISPATCH"
    | "INTEGRATION_EXECUTION"
    | "NODE_EXECUTION"
    | "EVENT";
  id: string;
  lockVersion: number;
  kind: string;
  aggregateType: string;
  aggregateId: string;
  eventId?: string | null;
  eventVersion?: number | null;
  status: string;
  attempts?: number | null;
  maxAttempts?: number | null;
  occurredAt: string;
  error?: Record<string, unknown> | null;
}

export interface OperatorCommand {
  reason: string;
  outcomePort?: "SUCCESS" | "ERROR";
  output?: Record<string, unknown>;
}

export interface CommandExecutionResult {
  executionId: string;
  resultJson: Record<string, unknown>;
  resultMetadataJson: Record<string, unknown>;
  replayed: boolean;
}
