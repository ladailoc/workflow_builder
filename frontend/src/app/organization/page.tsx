"use client";

import { AuthRouteGuard } from "@/features/auth";

export default function OrganizationPage() {
  return (
    <AuthRouteGuard roles={["ADMIN"]}>
      <div className="space-y-6">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            Organization & Administration
          </h1>
          <p className="mt-1 text-sm text-slate-500">
            Manage organizational hierarchy, roles, positions, and platform access.
          </p>
        </div>
        <div
          data-testid="organization-container"
          className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs"
        >
          <p className="text-sm text-slate-600">Organization admin ready.</p>
        </div>
      </div>
    </AuthRouteGuard>
  );
}
