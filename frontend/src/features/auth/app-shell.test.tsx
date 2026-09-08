import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { AuthSessionProvider } from "./auth-session";
import { AuthRouteGuard } from "./route-guard";
import { Sidebar } from "@/shared/components/layout/sidebar";
import { PRESET_ACTORS, type AuthSession } from "./types";

// Mock next/navigation
vi.mock("next/navigation", () => ({
  usePathname: () => "/catalog",
}));

describe("Application Shell & Permission Navigation", () => {
  it("shows primary runtime navigation to ordinary end users", () => {
    const endUserSession: AuthSession = {
      status: "authenticated",
      actor: PRESET_ACTORS[0], // Alice User: roles = ["USER"]
    };

    render(
      <AuthSessionProvider initialSession={endUserSession}>
        <Sidebar />
      </AuthSessionProvider>,
    );

    // Primary runtime navigation must be visible
    expect(screen.getByText("Request Catalog")).toBeInTheDocument();
    expect(screen.getByText("My Tickets")).toBeInTheDocument();
    expect(screen.getByText("My Tasks")).toBeInTheDocument();
    expect(screen.getByText("Events & History")).toBeInTheDocument();

    // Workflow Builder must NOT be shown to ordinary end user without permission
    expect(screen.queryByText("Workflow Builder")).not.toBeInTheDocument();
    expect(screen.queryByText("Organization")).not.toBeInTheDocument();
    expect(screen.queryByText("Operations")).not.toBeInTheDocument();
  });

  it("shows Workflow Builder only to users with WORKFLOW_OWNER or ADMIN role", () => {
    const ownerSession: AuthSession = {
      status: "authenticated",
      actor: PRESET_ACTORS[1], // Bob Owner: roles = ["WORKFLOW_OWNER"]
    };

    render(
      <AuthSessionProvider initialSession={ownerSession}>
        <Sidebar />
      </AuthSessionProvider>,
    );

    expect(screen.getByText("Workflow Builder")).toBeInTheDocument();
    expect(screen.queryByText("Organization")).not.toBeInTheDocument();
  });

  it("shows Operations only to users with OPERATOR or ADMIN role", () => {
    const operatorSession: AuthSession = {
      status: "authenticated",
      actor: PRESET_ACTORS[2], // Charlie Ops: roles = ["OPERATOR"]
    };

    render(
      <AuthSessionProvider initialSession={operatorSession}>
        <Sidebar />
      </AuthSessionProvider>,
    );

    expect(screen.getByText("Operations")).toBeInTheDocument();
    expect(screen.queryByText("Workflow Builder")).not.toBeInTheDocument();
  });

  it("shows all administration links to ADMIN role", () => {
    const adminSession: AuthSession = {
      status: "authenticated",
      actor: PRESET_ACTORS[3], // Diana Admin: roles = ["ADMIN"]
    };

    render(
      <AuthSessionProvider initialSession={adminSession}>
        <Sidebar />
      </AuthSessionProvider>,
    );

    expect(screen.getByText("Workflow Builder")).toBeInTheDocument();
    expect(screen.getByText("Organization")).toBeInTheDocument();
    expect(screen.getByText("Operations")).toBeInTheDocument();
  });

  it("blocks unauthorized access with 403 Forbidden in AuthRouteGuard", () => {
    const endUserSession: AuthSession = {
      status: "authenticated",
      actor: PRESET_ACTORS[0], // USER
    };

    render(
      <AuthSessionProvider initialSession={endUserSession}>
        <AuthRouteGuard roles={["WORKFLOW_OWNER"]}>
          <div data-testid="secret-builder">Builder Content</div>
        </AuthRouteGuard>
      </AuthSessionProvider>,
    );

    expect(screen.queryByTestId("secret-builder")).not.toBeInTheDocument();
    expect(screen.getByText("403 — Access Denied")).toBeInTheDocument();
  });

  it("allows authorized access through AuthRouteGuard", () => {
    const ownerSession: AuthSession = {
      status: "authenticated",
      actor: PRESET_ACTORS[1], // WORKFLOW_OWNER
    };

    render(
      <AuthSessionProvider initialSession={ownerSession}>
        <AuthRouteGuard roles={["WORKFLOW_OWNER"]}>
          <div data-testid="secret-builder">Builder Content</div>
        </AuthRouteGuard>
      </AuthSessionProvider>,
    );

    expect(screen.getByTestId("secret-builder")).toBeInTheDocument();
  });
});
