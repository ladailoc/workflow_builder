"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import {
  fetchCreateSchema,
  DynamicTicketForm,
  type CreateSchemaResponse,
} from "@/features/request-catalog";
import { LoadingState } from "@/shared/components/ui/loading-state";
import { ErrorState } from "@/shared/components/ui/error-state";

interface NewRequestPageProps {
  params: Promise<{
    requestTypeKey: string;
  }>;
}

export default function NewRequestPage({ params }: NewRequestPageProps) {
  const { requestTypeKey } = use(params);
  const [schema, setSchema] = useState<CreateSchemaResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadSchema = () => {
    setIsLoading(true);
    setError(null);
    fetchCreateSchema(requestTypeKey)
      .then((data) => {
        setSchema(data);
      })
      .catch((err: unknown) => {
        setError(
          err instanceof Error
            ? err.message
            : "Không thể tải biểu mẫu của dịch vụ này",
        );
      })
      .finally(() => {
        setIsLoading(false);
      });
  };

  useEffect(() => {
    let ignore = false;
    fetchCreateSchema(requestTypeKey)
      .then((data) => {
        if (!ignore) {
          setSchema(data);
          setIsLoading(false);
        }
      })
      .catch((err: unknown) => {
        if (!ignore) {
          setError(
            err instanceof Error
              ? err.message
              : "Không thể tải biểu mẫu của dịch vụ này",
          );
          setIsLoading(false);
        }
      });
    return () => {
      ignore = true;
    };
  }, [requestTypeKey]);

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2">
        <Link
          href="/catalog"
          className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-500 hover:text-slate-900 transition-colors"
        >
          <svg
            className="h-3.5 w-3.5"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M10 19l-7-7m0 0l7-7m-7 7h18"
            />
          </svg>
          <span>Quay lại danh mục</span>
        </Link>
      </div>

      <div>
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">
          Tạo yêu cầu mới
        </h1>
      </div>

      {isLoading ? (
        <LoadingState title="Đang tải biểu mẫu…" />
      ) : error ? (
        <ErrorState
          title="Không thể tải biểu mẫu"
          message={error}
          onRetry={loadSchema}
        />
      ) : schema ? (
        <DynamicTicketForm
          initialSchema={schema}
          requestTypeKey={requestTypeKey}
        />
      ) : null}
    </div>
  );
}
