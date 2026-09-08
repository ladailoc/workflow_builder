"use client";

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
  loadingFallback = null,
  unauthenticatedFallback = null,
  forbiddenFallback = null,
}: Readonly<AuthRouteGuardProps>) {
  const session = useAuthSession();

  if (session.status === "loading") return loadingFallback;
  if (session.status === "anonymous") return unauthenticatedFallback;

  const hasRoles = roles.every((role) => session.actor.roles.includes(role));
  const hasPermissions = permissions.every((permission) =>
    session.actor.permissions.includes(permission),
  );

  return hasRoles && hasPermissions ? children : forbiddenFallback;
}
