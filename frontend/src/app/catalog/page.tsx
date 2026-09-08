"use client";

import { useEffect, useState } from "react";
import { fetchRequestTypes, CatalogGrid, type CatalogItem } from "@/features/request-catalog";
import { ErrorState } from "@/shared/components/ui/error-state";

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
            : "Failed to load request catalog items",
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
              : "Failed to load request catalog items",
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
          Request Catalog
        </h1>
        <p className="mt-1 text-sm text-slate-500">
          Browse available service request types and initiate new ticket requests.
        </p>
      </div>

      {error ? (
        <ErrorState
          title="Could not load request catalog"
          message={error}
          onRetry={loadCatalog}
        />
      ) : (
        <CatalogGrid items={items} isLoading={isLoading} />
      )}
    </div>
  );
}
