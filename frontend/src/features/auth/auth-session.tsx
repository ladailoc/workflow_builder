"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { setActiveApiActor } from "@/shared/api/client";

import {
  ANONYMOUS_SESSION,
  PRESET_ACTORS,
  type AuthSession,
  type PlatformPermission,
  type PlatformRole,
  type SessionActor,
} from "./types";

interface AuthSessionContextValue {
  session: AuthSession;
  actor: SessionActor | null;
  switchActor: (actor: SessionActor) => void;
  setAnonymous: () => void;
  hasRole: (role: PlatformRole | readonly PlatformRole[]) => boolean;
  hasPermission: (permission: PlatformPermission) => boolean;
  canAccessWorkflowBuilder: boolean;
  canAccessOrganization: boolean;
  canAccessOperations: boolean;
}

const AuthSessionContext = createContext<AuthSessionContextValue | null>(null);

interface AuthSessionProviderProps {
  children: ReactNode;
  initialSession?: AuthSession;
}

export function AuthSessionProvider({
  children,
  initialSession,
}: Readonly<AuthSessionProviderProps>) {
  const [session, setSession] = useState<AuthSession>(() => {
    if (initialSession) return initialSession;
    // Default to Bob Owner (WORKFLOW_OWNER) for full platform exploration
    return {
      status: "authenticated",
      actor: PRESET_ACTORS[1],
    };
  });

  useEffect(() => {
    if (session.status === "authenticated" && session.actor) {
      setActiveApiActor({
        actorId: session.actor.actorId,
        principalName: session.actor.principalName,
        roles: session.actor.roles,
        permissions: session.actor.permissions,
      });
    } else {
      setActiveApiActor(null);
    }
  }, [session]);

  const switchActor = useCallback((actor: SessionActor) => {
    setSession({ status: "authenticated", actor });
  }, []);

  const setAnonymous = useCallback(() => {
    setSession(ANONYMOUS_SESSION);
  }, []);

  const hasRole = useCallback(
    (role: PlatformRole | readonly PlatformRole[]) => {
      if (session.status !== "authenticated" || !session.actor) return false;
      const required = Array.isArray(role) ? role : [role];
      return required.some((r) => session.actor.roles.includes(r));
    },
    [session],
  );

  const hasPermission = useCallback(
    (permission: PlatformPermission) => {
      if (session.status !== "authenticated" || !session.actor) return false;
      // ADMIN role implicitly has all permissions
      if (session.actor.roles.includes("ADMIN")) return true;
      return session.actor.permissions.includes(permission);
    },
    [session],
  );

  const canAccessWorkflowBuilder = useMemo(() => {
    return hasRole(["WORKFLOW_OWNER", "WORKFLOW_EDITOR", "ADMIN"]) ||
      hasPermission("WORKFLOW_BUILDER_ACCESS");
  }, [hasRole, hasPermission]);

  const canAccessOrganization = useMemo(() => {
    return hasRole(["ADMIN"]) || hasPermission("ORGANIZATION_ADMIN_ACCESS");
  }, [hasRole, hasPermission]);

  const canAccessOperations = useMemo(() => {
    return hasRole(["OPERATOR", "ADMIN"]) || hasPermission("OPERATIONS_ACCESS");
  }, [hasRole, hasPermission]);

  const value = useMemo<AuthSessionContextValue>(
    () => ({
      session,
      actor: session.status === "authenticated" ? session.actor : null,
      switchActor,
      setAnonymous,
      hasRole,
      hasPermission,
      canAccessWorkflowBuilder,
      canAccessOrganization,
      canAccessOperations,
    }),
    [
      session,
      switchActor,
      setAnonymous,
      hasRole,
      hasPermission,
      canAccessWorkflowBuilder,
      canAccessOrganization,
      canAccessOperations,
    ],
  );

  return <AuthSessionContext value={value}>{children}</AuthSessionContext>;
}

export function useAuthSession(): AuthSessionContextValue {
  const context = useContext(AuthSessionContext);
  if (context === null) {
    throw new Error("useAuthSession must be used inside AuthSessionProvider");
  }
  return context;
}
