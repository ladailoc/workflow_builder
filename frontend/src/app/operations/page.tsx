"use client";

import { AuthRouteGuard } from "@/features/auth";

export default function OperationsPage() {
  return (
    <AuthRouteGuard roles={["OPERATOR", "ADMIN"]}>
      <div className="space-y-6">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            Operations & Health
          </h1>
          <p className="mt-1 text-sm text-slate-500">
            Monitor dead jobs, outbox events, failed dispatches, and manual reconciliations.
          </p>
        </div>
        <div
          data-testid="operations-container"
          className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs"
        >
          <p className="text-sm text-slate-600">Operations monitor ready.</p>
        </div>
      </div>
    </AuthRouteGuard>
  );
}
