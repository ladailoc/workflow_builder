"use client";

import { useEffect, useState } from "react";
import { fetchRequestTypes, CatalogGrid, type CatalogItem } from "@/features/request-catalog";
import { ErrorState } from "@/shared/components/ui/error-state";
import { PageHeader } from "@/shared/components/ui/page-header";

export default function CatalogPage() {
  const [items, setItems] = useState<CatalogItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadCatalog = () => {
    setIsLoading(true);
    setError(null);
    fetchRequestTypes()
      .then((data) => {
        setItems(data);
      })
      .catch((err: unknown) => {
        setError(
          err instanceof Error
            ? err.message
            : "Không thể tải danh mục yêu cầu",
        );
      })
      .finally(() => {
        setIsLoading(false);
      });
  };

  useEffect(() => {
    let ignore = false;
    fetchRequestTypes()
      .then((data) => {
        if (!ignore) {
          setItems(data);
          setIsLoading(false);
        }
      })
      .catch((err: unknown) => {
        if (!ignore) {
          setError(
            err instanceof Error
              ? err.message
              : "Không thể tải danh mục yêu cầu",
          );
          setIsLoading(false);
        }
      });
    return () => {
      ignore = true;
    };
  }, []);

  return (
    <div className="space-y-8" data-testid="request-catalog-page">
      <PageHeader
        eyebrow="Tự phục vụ"
        title="Danh mục yêu cầu"
        description="Chọn nội dung bạn cần hỗ trợ. Hệ thống sẽ mở đúng biểu mẫu cho dịch vụ đó."
      />

      {error ? (
        <ErrorState
          title="Không thể tải danh mục yêu cầu"
          message={error}
          onRetry={loadCatalog}
        />
      ) : (
        <CatalogGrid items={items} isLoading={isLoading} />
      )}
    </div>
  );
}
