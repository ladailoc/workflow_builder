"use client";

import { useId, useMemo, useState } from "react";
import { executeTaskAction, fetchMyTasks } from "../api";
import type { TaskActionCommand, TaskItem } from "../types";
import { ApiRequestError } from "@/shared/api/client";

interface TaskInboxProps {
  initialTasks: TaskItem[];
}

function generateCommandId(): string {
  if (typeof crypto !== "undefined" && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return "cmd-" + Date.now();
}

export function TaskInbox({ initialTasks }: TaskInboxProps) {
  const [tasks, setTasks] = useState<TaskItem[]>(initialTasks);
  const [statusFilter, setStatusFilter] = useState<string>("ALL");
  const [searchQuery, setSearchQuery] = useState("");
  const [actionInProgress, setActionInProgress] = useState<string | null>(null);

  // Active action dialog state
  const [activeDialog, setActiveDialog] = useState<{
    task: TaskItem;
    action: TaskActionCommand["action"];
  } | null>(null);

  // Form input inside dialog (persisted across conflict errors)
  const [commentInput, setCommentInput] = useState("");
  const [targetUserInput, setTargetUserInput] = useState("");
  const [revisionFieldInput, setRevisionFieldInput] = useState("");

  // Concurrency Conflict state
  const [conflictError, setConflictError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const commentHtmlId = useId();
  const targetUserHtmlId = useId();
  const revisionFieldHtmlId = useId();

  const filteredTasks = useMemo(() => {
    return tasks.filter((t) => {
      const matchesStatus =
        statusFilter === "ALL" || t.status === statusFilter;
      const matchesSearch =
        !searchQuery ||
        t.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (t.description &&
          t.description.toLowerCase().includes(searchQuery.toLowerCase()));
      return matchesStatus && matchesSearch;
    });
  }, [tasks, statusFilter, searchQuery]);

  const handleRefresh = async () => {
    setConflictError(null);
    setActionError(null);
    try {
      const refreshed = await fetchMyTasks();
      setTasks(refreshed);
      // Update task in active dialog if it still exists
      if (activeDialog) {
        const found = refreshed.find((t) => t.id === activeDialog.task.id);
        if (found) {
          setActiveDialog({ ...activeDialog, task: found });
        }
      }
    } catch (err: unknown) {
      setActionError(
        err instanceof Error ? err.message : "Failed to refresh task list",
      );
    }
  };

  const openActionDialog = (
    task: TaskItem,
    action: TaskActionCommand["action"],
  ) => {
    setConflictError(null);
    setActionError(null);
    setActiveDialog({ task, action });
  };

  const closeActionDialog = () => {
    setActiveDialog(null);
    setCommentInput("");
    setTargetUserInput("");
    setRevisionFieldInput("");
    setConflictError(null);
    setActionError(null);
  };

  const handleExecuteCommand = async (
    task: TaskItem,
    action: TaskActionCommand["action"],
  ) => {
    const commandId = generateCommandId();

    const command: TaskActionCommand = {
      action,
      commandId,
      expectedVersion: task.lockVersion,
      comment: commentInput || undefined,
      targetUserId: targetUserInput || undefined,
      requestedFields: revisionFieldInput
        ? [
            {
              key: revisionFieldInput.trim().toLowerCase().replace(/\s+/g, "_"),
              label: revisionFieldInput.trim(),
              type: "TEXT",
              required: true,
            },
          ]
        : undefined,
    };

    setActionInProgress(task.id);
    setConflictError(null);
    setActionError(null);

    try {
      // Backend confirmation must be received before state changes
      const updatedTask = await executeTaskAction(task.id, command);

      // Successfully confirmed by backend -> update local state
      setTasks((prev) =>
        prev.map((t) => (t.id === updatedTask.id ? updatedTask : t)),
      );
      closeActionDialog();
    } catch (err: unknown) {
      if (err instanceof ApiRequestError) {
        if (
          err.status === 409 ||
          err.code === "CONFLICT" ||
          err.code === "STALE_VERSION" ||
          err.message.includes("409") ||
          err.message.toLowerCase().includes("conflict")
        ) {
          // Handle 409 Conflict: DO NOT mutate local state; preserve user input; prompt refresh
          setConflictError(
            "Conflict detected: This task has been updated concurrently by another user or workflow process. Please refresh the latest state.",
          );
          return;
        }
      }
      setActionError(
        err instanceof Error ? err.message : "Failed to perform task action",
      );
    } finally {
      setActionInProgress(null);
    }
  };

  return (
    <div className="space-y-6" data-testid="task-inbox">
      {/* Filters and Search Bar */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-wrap gap-1.5" data-testid="task-status-filters">
          {(["ALL", "READY", "CLAIMED", "IN_PROGRESS"] as const).map(
            (status) => (
              <button
                key={status}
                type="button"
                data-testid={`filter-${status.toLowerCase()}`}
                onClick={() => setStatusFilter(status)}
                className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors ${
                  statusFilter === status
                    ? "bg-blue-600 text-white shadow-2xs"
                    : "bg-white text-slate-600 border border-slate-200 hover:bg-slate-50"
                }`}
              >
                {status === "ALL" ? "All Statuses" : status}
              </button>
            ),
          )}
        </div>

        <div className="flex items-center gap-2">
          <input
            type="text"
            placeholder="Search tasks..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-48 sm:w-64 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs text-slate-800 placeholder-slate-400 focus:border-blue-500 focus:outline-none"
          />
          <button
            type="button"
            data-testid="refresh-tasks-button"
            onClick={handleRefresh}
            className="rounded-lg border border-slate-200 bg-white p-2 text-slate-500 hover:bg-slate-50 transition-colors"
            title="Refresh Tasks"
          >
            <svg
              className="h-4 w-4"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
              />
            </svg>
          </button>
        </div>
      </div>

      {/* Task List */}
      {filteredTasks.length === 0 ? (
        <div
          data-testid="no-tasks-message"
          className="rounded-xl border border-dashed border-slate-300 p-12 text-center text-xs text-slate-500"
        >
          No tasks found matching your filter criteria.
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4" data-testid="task-cards-list">
          {filteredTasks.map((task) => {
            const isBusy = actionInProgress === task.id;

            return (
              <div
                key={task.id}
                data-testid={`task-card-${task.id}`}
                className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs transition-shadow hover:shadow-sm"
              >
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <span
                        data-testid={`task-status-${task.id}`}
                        className={`rounded-full px-2 py-0.5 text-[10px] font-bold ${
                          task.status === "READY"
                            ? "bg-amber-50 text-amber-700 border border-amber-200"
                            : task.status === "CLAIMED"
                              ? "bg-blue-50 text-blue-700 border border-blue-200"
                              : task.status === "IN_PROGRESS"
                                ? "bg-indigo-50 text-indigo-700 border border-indigo-200"
                                : "bg-slate-100 text-slate-700"
                        }`}
                      >
                        {task.status}
                      </span>
                      <span className="text-[11px] text-slate-400 font-mono">
                        #{task.id.slice(0, 8)} (v{task.lockVersion})
                      </span>
                    </div>

                    <h3 className="text-sm font-semibold text-slate-900">
                      {task.title}
                    </h3>
                    {task.description && (
                      <p className="text-xs text-slate-500">
                        {task.description}
                      </p>
                    )}
                    {task.dueAt && (
                      <p className="text-[11px] text-rose-600 font-medium">
                        Due: {new Date(task.dueAt).toLocaleDateString()}
                      </p>
                    )}
                  </div>

                  {/* Actions Bar */}
                  <div className="flex flex-wrap items-center gap-2">
                    {task.status === "READY" && (
                      <button
                        type="button"
                        data-testid={`action-claim-${task.id}`}
                        disabled={isBusy}
                        onClick={() => handleExecuteCommand(task, "claim")}
                        className="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white shadow-2xs hover:bg-blue-700 disabled:opacity-50"
                      >
                        Claim
                      </button>
                    )}

                    {(task.status === "CLAIMED" ||
                      task.status === "IN_PROGRESS") && (
                      <>
                        <button
                          type="button"
                          data-testid={`action-approve-${task.id}`}
                          disabled={isBusy}
                          onClick={() => openActionDialog(task, "approve")}
                          className="rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white shadow-2xs hover:bg-emerald-700 disabled:opacity-50"
                        >
                          Approve
                        </button>
                        <button
                          type="button"
                          data-testid={`action-reject-${task.id}`}
                          disabled={isBusy}
                          onClick={() => openActionDialog(task, "reject")}
                          className="rounded-lg bg-rose-600 px-3 py-1.5 text-xs font-semibold text-white shadow-2xs hover:bg-rose-700 disabled:opacity-50"
                        >
                          Reject
                        </button>
                        <button
                          type="button"
                          data-testid={`action-request-revision-${task.id}`}
                          disabled={isBusy}
                          onClick={() =>
                            openActionDialog(task, "request-revision")
                          }
                          className="rounded-lg border border-amber-300 bg-amber-50 px-3 py-1.5 text-xs font-semibold text-amber-800 hover:bg-amber-100 disabled:opacity-50"
                        >
                          Request Revision
                        </button>
                        <button
                          type="button"
                          data-testid={`action-complete-${task.id}`}
                          disabled={isBusy}
                          onClick={() => handleExecuteCommand(task, "complete")}
                          className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-100 disabled:opacity-50"
                        >
                          Complete
                        </button>
                      </>
                    )}

                    <button
                      type="button"
                      data-testid={`action-reassign-${task.id}`}
                      disabled={isBusy}
                      onClick={() => openActionDialog(task, "reassign")}
                      className="rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50"
                    >
                      Reassign
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Action Dialog / Modal */}
      {activeDialog && (
        <div
          data-testid="task-action-modal"
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
        >
          <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-xl space-y-4">
            <div>
              <h3 className="text-base font-semibold text-slate-900 uppercase">
                {activeDialog.action.replace("-", " ")} Task
              </h3>
              <p className="text-xs text-slate-500 mt-0.5">
                Task: #{activeDialog.task.id.slice(0, 8)} • Expected Lock Version: {activeDialog.task.lockVersion}
              </p>
            </div>

            {/* 409 Conflict Banner */}
            {conflictError && (
              <div
                data-testid="conflict-error-banner"
                className="rounded-xl border border-rose-300 bg-rose-50 p-3 text-xs text-rose-900 space-y-2"
              >
                <div className="font-semibold">{conflictError}</div>
                <button
                  type="button"
                  data-testid="conflict-refresh-button"
                  onClick={handleRefresh}
                  className="rounded-md bg-rose-600 px-2.5 py-1 text-xs font-semibold text-white hover:bg-rose-700"
                >
                  Refresh Task State
                </button>
              </div>
            )}

            {actionError && !conflictError && (
              <div
                data-testid="action-error-banner"
                className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs text-rose-700"
              >
                {actionError}
              </div>
            )}

            {/* Dialog inputs */}
            <div className="space-y-3">
              {activeDialog.action === "reassign" && (
                <div>
                  <label htmlFor={targetUserHtmlId} className="block text-xs font-semibold text-slate-700 mb-1">
                    New Assignee User ID
                  </label>
                  <input
                    id={targetUserHtmlId}
                    type="text"
                    data-testid="reassign-target-user-input"
                    value={targetUserInput}
                    placeholder="Enter user UUID"
                    onChange={(e) => setTargetUserInput(e.target.value)}
                    className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs text-slate-800 focus:border-blue-500 focus:outline-none"
                  />
                </div>
              )}

              {activeDialog.action === "request-revision" && (
                <div>
                  <label htmlFor={revisionFieldHtmlId} className="block text-xs font-semibold text-slate-700 mb-1">
                    Requested Field
                  </label>
                  <input
                    id={revisionFieldHtmlId}
                    type="text"
                    data-testid="revision-field-input"
                    value={revisionFieldInput}
                    placeholder="e.g. Budget justification document"
                    onChange={(e) => setRevisionFieldInput(e.target.value)}
                    className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs text-slate-800 focus:border-blue-500 focus:outline-none"
                  />
                </div>
              )}

              <div>
                <label htmlFor={commentHtmlId} className="block text-xs font-semibold text-slate-700 mb-1">
                  Reason / Comment
                </label>
                <textarea
                  id={commentHtmlId}
                  data-testid="action-comment-input"
                  rows={3}
                  value={commentInput}
                  placeholder="Provide decision comments..."
                  onChange={(e) => setCommentInput(e.target.value)}
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 text-xs text-slate-800 focus:border-blue-500 focus:outline-none"
                />
              </div>
            </div>

            <div className="flex items-center justify-end gap-2 border-t border-slate-100 pt-3">
              <button
                type="button"
                onClick={closeActionDialog}
                className="rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                data-testid="confirm-dialog-button"
                disabled={actionInProgress === activeDialog.task.id}
                onClick={() =>
                  handleExecuteCommand(activeDialog.task, activeDialog.action)
                }
                className="rounded-lg bg-blue-600 px-4 py-1.5 text-xs font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {actionInProgress === activeDialog.task.id
                  ? "Processing..."
                  : "Confirm"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
