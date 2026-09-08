"use client";

import Link from "next/link";
import type { ReactNode } from "react";

import { useAuthSession } from "./auth-session";
import type { PlatformPermission, PlatformRole } from "./types";

interface AuthRouteGuardProps {
  children: ReactNode;
  roles?: readonly PlatformRole[];
  permissions?: readonly PlatformPermission[];
  loadingFallback?: ReactNode;
  unauthenticatedFallback?: ReactNode;
  forbiddenFallback?: ReactNode;
}

export function AuthRouteGuard({
  children,
  roles = [],
  permissions = [],
  loadingFallback,
  unauthenticatedFallback,
  forbiddenFallback,
}: Readonly<AuthRouteGuardProps>) {
  const { session, hasRole, hasPermission } = useAuthSession();

  if (session.status === "loading") {
    return (
      loadingFallback ?? (
        <div
          data-testid="auth-loading"
          className="flex min-h-[50vh] items-center justify-center p-8"
        >
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-600 border-t-transparent" />
        </div>
      )
    );
  }

  if (session.status === "anonymous") {
    return (
      unauthenticatedFallback ?? (
        <div
          data-testid="auth-unauthenticated"
          className="mx-auto max-w-md rounded-xl border border-slate-200 bg-white p-8 text-center shadow-sm"
        >
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-amber-100 text-amber-600">
            <svg
              className="h-6 w-6"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
              />
            </svg>
          </div>
          <h2 className="mt-4 text-lg font-semibold text-slate-900">
            Authentication Required
          </h2>
          <p className="mt-2 text-sm text-slate-600">
            Please select an active identity to access this section.
          </p>
        </div>
      )
    );
  }

  const roleAllowed = roles.length === 0 || hasRole(roles);
  const permissionAllowed =
    permissions.length === 0 || permissions.some((p) => hasPermission(p));

  const isAllowed = roleAllowed && permissionAllowed;

  if (!isAllowed) {
    return (
      forbiddenFallback ?? (
        <div
          data-testid="auth-forbidden"
          className="mx-auto max-w-lg rounded-xl border border-red-200 bg-red-50/50 p-8 text-center"
        >
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-red-100 text-red-600">
            <svg
              className="h-6 w-6"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636"
              />
            </svg>
          </div>
          <h2 className="mt-4 text-xl font-semibold text-slate-900">
            403 — Access Denied
          </h2>
          <p className="mt-2 text-sm text-slate-600">
            You do not have the required permissions to view this page.
            Privileged features like Workflow Builder require{" "}
            <span className="font-semibold text-slate-800">
              WORKFLOW_OWNER
            </span>{" "}
            or <span className="font-semibold text-slate-800">ADMIN</span>{" "}
            roles.
          </p>
          <p className="mt-2 text-xs text-slate-500">
            Switch your simulated actor in the bottom-left of the sidebar to test
            different permission views.
          </p>
          <div className="mt-6">
            <Link
              href="/catalog"
              className="inline-flex items-center rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
            >
              Return to Request Catalog
            </Link>
          </div>
        </div>
      )
    );
  }

  return children;
}
