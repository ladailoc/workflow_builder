"use client";

import { useEffect, useState } from "react";
import { fetchMyTasks, TaskInbox, type TaskItem } from "@/features/runtime";
import { LoadingState } from "@/shared/components/ui/loading-state";
import { ErrorState } from "@/shared/components/ui/error-state";

export default function TasksPage() {
  const [tasks, setTasks] = useState<TaskItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadTasks = () => {
    setIsLoading(true);
    setError(null);
    fetchMyTasks()
      .then((data) => {
        setTasks(data);
      })
      .catch((err: unknown) => {
        setError(
          err instanceof Error ? err.message : "Failed to load assigned tasks",
        );
      })
      .finally(() => {
        setIsLoading(false);
      });
  };

  useEffect(() => {
    let ignore = false;
    fetchMyTasks()
      .then((data) => {
        if (!ignore) {
          setTasks(data);
          setIsLoading(false);
        }
      })
      .catch((err: unknown) => {
        if (!ignore) {
          setError(
            err instanceof Error ? err.message : "Failed to load assigned tasks",
          );
          setIsLoading(false);
        }
      });
    return () => {
      ignore = true;
    };
  }, []);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">
          My Tasks
        </h1>
        <p className="mt-1 text-sm text-slate-500">
          Review, approve, reject, claim, and complete workflow tasks assigned to you.
        </p>
      </div>

      {isLoading ? (
        <LoadingState title="Loading task inbox..." />
      ) : error ? (
        <ErrorState
          title="Could not load tasks"
          message={error}
          onRetry={loadTasks}
        />
      ) : (
        <TaskInbox initialTasks={tasks} />
      )}
    </div>
  );
}
