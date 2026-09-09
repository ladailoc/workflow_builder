export const PLATFORM_ROLES = [
  "USER",
  "WORKFLOW_OWNER",
  "WORKFLOW_EDITOR",
  "OPERATOR",
  "ADMIN",
] as const;

export type PlatformRole = (typeof PLATFORM_ROLES)[number] | (string & {});
export type PlatformPermission =
  | "WORKFLOW_BUILDER_ACCESS"
  | "ORGANIZATION_ADMIN_ACCESS"
  | "OPERATIONS_ACCESS"
  | "REQUEST_CATALOG_ACCESS"
  | "TASK_ACTION_ACCESS"
  | (string & {});

export interface SessionActor {
  actorId: string;
  principalName: string;
  email: string;
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

export const PRESET_ACTORS: readonly SessionActor[] = [
  {
    actorId: "10000000-0000-4000-8000-000000000001",
    principalName: "Alice User",
    email: "alice@company.com",
    roles: ["USER"],
    permissions: ["REQUEST_CATALOG_ACCESS", "TASK_ACTION_ACCESS"],
  },
  {
    actorId: "20000000-0000-4000-8000-000000000002",
    principalName: "Bob Owner",
    email: "bob.owner@company.com",
    roles: ["WORKFLOW_OWNER"],
    permissions: [
      "REQUEST_CATALOG_ACCESS",
      "TASK_ACTION_ACCESS",
      "WORKFLOW_BUILDER_ACCESS",
    ],
  },
  {
    actorId: "30000000-0000-4000-8000-000000000003",
    principalName: "Charlie Ops",
    email: "charlie.ops@company.com",
    roles: ["OPERATOR"],
    permissions: [
      "REQUEST_CATALOG_ACCESS",
      "TASK_ACTION_ACCESS",
      "OPERATIONS_ACCESS",
    ],
  },
  {
    actorId: "40000000-0000-4000-8000-000000000004",
    principalName: "Diana Admin",
    email: "diana.admin@company.com",
    roles: ["ADMIN"],
    permissions: [
      "REQUEST_CATALOG_ACCESS",
      "TASK_ACTION_ACCESS",
      "WORKFLOW_BUILDER_ACCESS",
      "ORGANIZATION_ADMIN_ACCESS",
      "OPERATIONS_ACCESS",
    ],
  },
] as const;
