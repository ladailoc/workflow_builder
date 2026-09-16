"use client";

import { useEffect, useState } from "react";
import { fetchMyTasks, TaskInbox, type TaskItem } from "@/features/runtime";
import { LoadingState } from "@/shared/components/ui/loading-state";
import { ErrorState } from "@/shared/components/ui/error-state";
import { PageHeader } from "@/shared/components/ui/page-header";

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
          err instanceof Error ? err.message : "Không thể tải công việc được giao",
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
            err instanceof Error ? err.message : "Không thể tải công việc được giao",
          );
          setIsLoading(false);
        }
      });
    return () => {
      ignore = true;
    };
  }, []);

  return (
    <div className="space-y-7" data-testid="tasks-page">
      <PageHeader
        eyebrow="Danh sách của bạn"
        title="Công việc của tôi"
        description="Xem, phê duyệt, yêu cầu bổ sung hoặc hoàn tất công việc được giao."
      />

      {isLoading ? (
        <LoadingState title="Đang tải công việc…" />
      ) : error ? (
        <ErrorState
          title="Không thể tải công việc"
          message={error}
          onRetry={loadTasks}
        />
      ) : (
        <TaskInbox initialTasks={tasks} />
      )}
    </div>
  );
}
