"use client";

import { createContext, useContext, type ReactNode } from "react";

import { ANONYMOUS_SESSION, type AuthSession } from "./types";

const AuthSessionContext = createContext<AuthSession | null>(null);

interface AuthSessionProviderProps {
  children: ReactNode;
  session?: AuthSession;
}

export function AuthSessionProvider({
  children,
  session = ANONYMOUS_SESSION,
}: Readonly<AuthSessionProviderProps>) {
  return <AuthSessionContext value={session}>{children}</AuthSessionContext>;
}

export function useAuthSession(): AuthSession {
  const session = useContext(AuthSessionContext);
  if (session === null) {
    throw new Error("useAuthSession must be used inside AuthSessionProvider");
  }
  return session;
}
