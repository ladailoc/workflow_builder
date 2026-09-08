import { apiGet, apiPost } from "@/shared/api/client";
import type {
  EventMonitoringView,
  TaskActionCommand,
  TaskItem,
  TicketAggregate,
  TicketView,
} from "./types";

export async function fetchTicket(id: string): Promise<TicketAggregate> {
  return apiGet<TicketAggregate>(`/api/v1/tickets/${encodeURIComponent(id)}`);
}

export async function fetchMyTickets(): Promise<TicketView[]> {
  return apiGet<TicketView[]>("/api/v1/tickets");
}

export async function fetchEventMonitoring(
  eventId: string,
): Promise<EventMonitoringView> {
  return apiGet<EventMonitoringView>(
    `/api/v1/events/${encodeURIComponent(eventId)}/monitoring`,
  );
}

export async function fetchMyTasks(statusFilter?: string): Promise<TaskItem[]> {
  const query = statusFilter ? `?status=${encodeURIComponent(statusFilter)}` : "";
  return apiGet<TaskItem[]>(`/api/v1/tasks${query}`);
}

export async function executeTaskAction(
  taskId: string,
  command: TaskActionCommand,
): Promise<TaskItem> {
  const payload: Record<string, unknown> = {};
  if (command.comment !== undefined) payload.comment = command.comment;
  if (command.targetUserId !== undefined) payload.targetUserId = command.targetUserId;
  if (command.formData !== undefined) payload.formData = command.formData;
  if (command.requestedFields !== undefined) payload.requestedFields = command.requestedFields;

  return apiPost<TaskItem>(
    `/api/v1/tasks/${encodeURIComponent(taskId)}/${encodeURIComponent(command.action)}`,
    payload,
    {
      headers: {
        "X-Command-Id": command.commandId,
        "If-Match": String(command.expectedVersion),
      },
    },
  );
}
