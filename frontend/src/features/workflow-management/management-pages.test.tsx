import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import WorkflowsPage from "@/app/workflows/page";
import NewWorkflowPage from "@/app/workflows/new/page";
import WorkflowDetailPage from "@/app/workflows/[workflowId]/page";
import RequestTypesPage from "@/app/request-types/page";
import { AuthSessionProvider } from "@/features/auth";
import { PRESET_ACTORS, type AuthSession } from "@/features/auth/types";
import type {
  PageResult,
  RequestTypeAdminView,
  WorkflowSummary,
} from "./types";

const managementMocks = vi.hoisted(() => ({
  fetchWorkflows: vi.fn(),
  fetchAdminRequestTypes: vi.fn(),
  fetchWorkflow: vi.fn(),
  createWorkflow: vi.fn(),
  changeWorkflowLifecycle: vi.fn(),
  cloneVersionAsDraft: vi.fn(),
  fetchVersionDiff: vi.fn(),
}));

const navigationMocks = vi.hoisted(() => ({
  push: vi.fn(),
  back: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useParams: () => ({
    workflowId: "10000000-0000-4000-8000-000000000100",
    versionId: "10000000-0000-4000-8000-000000000101",
    requestTypeId: "20000000-0000-4000-8000-000000000100",
  }),
  usePathname: () => "/workflows",
  useRouter: () => navigationMocks,
}));

vi.mock("@/features/workflow-management", async () => {
  const actual = await vi.importActual<
    typeof import("@/features/workflow-management")
  >("@/features/workflow-management");
  return { ...actual, ...managementMocks };
});

const ownerSession: AuthSession = {
  status: "authenticated",
  actor: PRESET_ACTORS[1],
};

function pageOf<T>(items: T[]): PageResult<T> {
  return {
    items,
    page: 0,
    size: 50,
    totalElements: items.length,
    totalPages: items.length === 0 ? 0 : 1,
    hasNext: false,
  };
}

describe("Workflow and Request Type management pages", () => {
  beforeEach(() => {
    Object.values(managementMocks).forEach((mock) => mock.mockReset());
    navigationMocks.push.mockReset();
    navigationMocks.back.mockReset();
  });

  it("creates only a WorkflowDefinition and navigates to its management detail", async () => {
    managementMocks.createWorkflow.mockResolvedValue({
      id: "10000000-0000-4000-8000-000000000199",
    });

    render(
      <AuthSessionProvider initialSession={ownerSession}>
        <NewWorkflowPage />
      </AuthSessionProvider>,
    );

    fireEvent.change(screen.getByLabelText("Tên"), {
      target: { value: "Leave Approval" },
    });
    fireEvent.change(screen.getByLabelText("Khóa ổn định"), {
      target: { value: "leave approval" },
    });
    fireEvent.change(screen.getByLabelText("Mô tả"), {
      target: { value: "Administrative definition only" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Tạo quy trình" }));

    await waitFor(() =>
      expect(managementMocks.createWorkflow).toHaveBeenCalledWith({
        key: "LEAVE_APPROVAL",
        name: "Leave Approval",
        description: "Administrative definition only",
        ownerId: PRESET_ACTORS[1].actorId,
      }),
    );
    expect(navigationMocks.push).toHaveBeenCalledWith(
      "/workflows/10000000-0000-4000-8000-000000000199",
    );
  });

  it("shows immutable version history, backend semantic diff, and contextual Builder links", async () => {
    const workflow = workflowFixture();
    managementMocks.fetchWorkflow.mockResolvedValue(workflow);
    managementMocks.fetchVersionDiff.mockResolvedValue({
      nodes: { added: ["approval"] },
    });

    render(
      <AuthSessionProvider initialSession={ownerSession}>
        <WorkflowDetailPage />
      </AuthSessionProvider>,
    );

    expect(
      await screen.findByTestId("workflow-detail-page"),
    ).toBeInTheDocument();
    expect(screen.getByText("Lịch sử phiên bản")).toBeInTheDocument();
    expect(screen.getAllByText("PUBLISHED").length).toBeGreaterThan(0);
    expect(screen.getAllByText("SUPERSEDED").length).toBeGreaterThan(0);
    expect(
      screen.getAllByRole("link", { name: "Xem sơ đồ" })[0],
    ).toHaveAttribute(
      "href",
      "/workflows/10000000-0000-4000-8000-000000000100/versions/10000000-0000-4000-8000-000000000102/builder",
    );

    fireEvent.click(screen.getByRole("button", { name: "So sánh" }));
    expect(await screen.findByTestId("semantic-diff-result")).toHaveTextContent(
      "approval",
    );
    expect(managementMocks.fetchVersionDiff).toHaveBeenCalled();
  });

  it("confirms lifecycle changes and clones an immutable version as a new Draft", async () => {
    const workflow = workflowFixture();
    managementMocks.fetchWorkflow.mockResolvedValue(workflow);
    managementMocks.changeWorkflowLifecycle.mockResolvedValue(
      workflow.workflow,
    );
    managementMocks.cloneVersionAsDraft.mockResolvedValue({
      draftVersionId: "10000000-0000-4000-8000-000000000103",
      versionNo: 3,
    });
    vi.spyOn(window, "confirm").mockReturnValue(true);

    render(
      <AuthSessionProvider initialSession={ownerSession}>
        <WorkflowDetailPage />
      </AuthSessionProvider>,
    );

    expect(
      await screen.findByTestId("workflow-detail-page"),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Tạm dừng" }));
    await waitFor(() =>
      expect(managementMocks.changeWorkflowLifecycle).toHaveBeenCalledWith(
        workflow.workflow.id,
        "suspend",
        workflow.workflow.lockVersion,
        "Suspended from Workflow Management",
      ),
    );

    fireEvent.click(
      screen.getAllByRole("button", { name: "Sao chép thành bản nháp" })[0],
    );
    await waitFor(() =>
      expect(navigationMocks.push).toHaveBeenCalledWith(
        "/workflows/10000000-0000-4000-8000-000000000100/versions/10000000-0000-4000-8000-000000000103/builder",
      ),
    );
  });

  it("renders a real WorkflowDefinition list instead of a mock builder shell", async () => {
    const workflow: WorkflowSummary = {
      id: "10000000-0000-4000-8000-000000000100",
      key: "PURCHASE_APPROVAL",
      name: "Purchase Approval",
      description: "Definition-driven approval flow",
      lifecycle: "ACTIVE",
      ownerId: PRESET_ACTORS[1].actorId,
      currentPublishedVersionId: "10000000-0000-4000-8000-000000000101",
      currentPublishedVersionNo: 3,
      activeDraftVersionId: null,
      activeDraftVersionNo: null,
      activeDraftRevision: null,
      versionCount: 3,
      createdAt: "2026-09-09T08:00:00Z",
      updatedAt: "2026-09-09T09:00:00Z",
      lockVersion: 2,
    };
    managementMocks.fetchWorkflows.mockResolvedValue(pageOf([workflow]));

    render(
      <AuthSessionProvider initialSession={ownerSession}>
        <WorkflowsPage />
      </AuthSessionProvider>,
    );

    expect(await screen.findByText("Purchase Approval")).toBeInTheDocument();
    expect(screen.getByText("PURCHASE_APPROVAL")).toBeInTheDocument();
    expect(screen.getByText("V3")).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "+ Tạo quy trình" }),
    ).toHaveAttribute("href", "/workflows/new");
    expect(screen.queryByTestId("workflow-builder")).not.toBeInTheDocument();
  });

  it("renders business Request Types with their definition mapping and schema state", async () => {
    const requestType: RequestTypeAdminView = {
      id: "20000000-0000-4000-8000-000000000100",
      key: "PURCHASE_REQUEST",
      name: "Purchase Request",
      description: "Business-facing catalog entry",
      category: "Finance",
      active: true,
      creationPolicyJson: {},
      workflowDefinitionId: "10000000-0000-4000-8000-000000000100",
      workflowDefinitionName: "Purchase Approval",
      workflowLifecycle: "ACTIVE",
      currentPublishedVersionId: "10000000-0000-4000-8000-000000000101",
      currentPublishedVersionNo: 3,
      schemaAvailable: true,
      createdAt: "2026-09-09T08:00:00Z",
      updatedAt: "2026-09-09T09:00:00Z",
      lockVersion: 1,
    };
    managementMocks.fetchAdminRequestTypes.mockResolvedValue(
      pageOf([requestType]),
    );

    render(
      <AuthSessionProvider initialSession={ownerSession}>
        <RequestTypesPage />
      </AuthSessionProvider>,
    );

    expect(await screen.findByText("Purchase Request")).toBeInTheDocument();
    expect(screen.getByText("PURCHASE_REQUEST")).toBeInTheDocument();
    expect(screen.getByText("Purchase Approval")).toBeInTheDocument();
    expect(screen.getByText("V3")).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "+ Tạo loại yêu cầu" }),
    ).toHaveAttribute("href", "/request-types/new");
  });
});

function workflowFixture() {
  const workflow: WorkflowSummary = {
    id: "10000000-0000-4000-8000-000000000100",
    key: "PURCHASE_APPROVAL",
    name: "Purchase Approval",
    description: "Definition-driven approval flow",
    lifecycle: "ACTIVE",
    ownerId: PRESET_ACTORS[1].actorId,
    currentPublishedVersionId: "10000000-0000-4000-8000-000000000102",
    currentPublishedVersionNo: 2,
    activeDraftVersionId: null,
    activeDraftVersionNo: null,
    activeDraftRevision: null,
    versionCount: 2,
    createdAt: "2026-09-09T08:00:00Z",
    updatedAt: "2026-09-09T09:00:00Z",
    lockVersion: 2,
  };
  return {
    workflow,
    versions: [
      {
        id: "10000000-0000-4000-8000-000000000102",
        definitionId: workflow.id,
        versionNo: 2,
        status: "PUBLISHED" as const,
        revision: 4,
        checksum: "checksum-v2",
        basedOnVersionId: "10000000-0000-4000-8000-000000000101",
        createdBy: PRESET_ACTORS[1].actorId,
        createdAt: "2026-09-09T08:30:00Z",
        publishedAt: "2026-09-09T09:00:00Z",
        publishedBy: PRESET_ACTORS[1].actorId,
        lockVersion: 5,
      },
      {
        id: "10000000-0000-4000-8000-000000000101",
        definitionId: workflow.id,
        versionNo: 1,
        status: "SUPERSEDED" as const,
        revision: 3,
        checksum: "checksum-v1",
        createdBy: PRESET_ACTORS[1].actorId,
        createdAt: "2026-09-09T08:00:00Z",
        publishedAt: "2026-09-09T08:15:00Z",
        publishedBy: PRESET_ACTORS[1].actorId,
        lockVersion: 4,
      },
    ],
  };
}
