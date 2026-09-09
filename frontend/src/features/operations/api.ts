import { apiGet, apiPost } from "@/shared/api/client";
import type { EventMonitoringView } from "@/features/runtime/types";
import type {
  CommandExecutionResult,
  OperationalFailure,
  OperatorCommand,
} from "./types";

export async function fetchOperationalFailures(): Promise<
  OperationalFailure[]
> {
  return apiGet<OperationalFailure[]>("/api/v1/operations/failures");
}

export async function fetchOperationalEvent(
  eventId: string,
): Promise<EventMonitoringView> {
  return apiGet<EventMonitoringView>(
    `/api/v1/events/${encodeURIComponent(eventId)}/monitoring`,
  );
}

export async function executeOperationalCommand(
  path: string,
  expectedVersion: number,
  command: OperatorCommand,
): Promise<CommandExecutionResult> {
  const commandId = crypto.randomUUID();
  return apiPost<CommandExecutionResult>(
    path as `/${string}`,
    { commandId, ...command, output: command.output ?? {} },
    {
      commandId,
      headers: { "If-Match": String(expectedVersion) },
    },
  );
}
