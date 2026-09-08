export const PLATFORM_ROLES = [
  "USER",
  "WORKFLOW_OWNER",
  "WORKFLOW_EDITOR",
  "OPERATOR",
  "ADMIN",
] as const;

export type PlatformRole = (typeof PLATFORM_ROLES)[number] | (string & {});
export type PlatformPermission = string;

export interface SessionActor {
  actorId: string;
  principalName: string;
  roles: readonly PlatformRole[];
  permissions: readonly PlatformPermission[];
}

export type AuthSession =
  | { status: "loading"; actor: null }
  | { status: "anonymous"; actor: null }
  | { status: "authenticated"; actor: SessionActor };

export const ANONYMOUS_SESSION: AuthSession = {
  status: "anonymous",
  actor: null,
};
