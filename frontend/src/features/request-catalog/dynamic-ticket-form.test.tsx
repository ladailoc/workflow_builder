import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { DynamicTicketForm } from "./components/dynamic-ticket-form";
import type { CreateSchemaResponse } from "./types";
import * as api from "./api";
import { ApiRequestError } from "@/shared/api/client";

// Mock next/navigation
const mockPush = vi.fn();
const mockBack = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: mockPush,
    back: mockBack,
  }),
}));

const INITIAL_SCHEMA: CreateSchemaResponse = {
  requestTypeId: "req-type-1",
  requestTypeKey: "laptop_procurement",
  sourceWorkflowVersionId: "wf-ver-1",
  formSchemaVersion: 1,
  formSchemaChecksum: "checksum-v1",
  ticketFormSchema: {
    formKey: "laptop_request_form",
    formType: "TICKET_FORM",
    fields: [
      {
        fieldId: "f-1",
        key: "justification",
        label: "Business Justification",
        description: "Why do you need this equipment?",
        order: 1,
        type: { type: "STRING" },
        sensitive: false,
        requirement: { mode: "ALWAYS" },
        visibility: { mode: "ALWAYS" },
        editability: { mode: "EDITABLE" },
        validation: { minimumLength: 5 },
      },
      {
        fieldId: "f-2",
        key: "isUrgent",
        label: "Is this request urgent?",
        order: 2,
        type: { type: "BOOLEAN" },
        defaultValue: false,
        sensitive: false,
        requirement: { mode: "NEVER" },
        visibility: { mode: "ALWAYS" },
        editability: { mode: "EDITABLE" },
        validation: {},
      },
      {
        fieldId: "f-3",
        key: "urgencyReason",
        label: "Reason for Urgency",
        order: 3,
        type: { type: "STRING" },
        sensitive: false,
        requirement: {
          mode: "CONDITIONAL",
          condition: {
            kind: "OPERATOR",
            operator: "EQ",
            operands: [
              { kind: "REFERENCE", path: "isUrgent" },
              { kind: "LITERAL", value: true, type: { type: "BOOLEAN" } },
            ],
          },
        },
        visibility: {
          mode: "CONDITIONAL",
          condition: {
            kind: "OPERATOR",
            operator: "EQ",
            operands: [
              { kind: "REFERENCE", path: "isUrgent" },
              { kind: "LITERAL", value: true, type: { type: "BOOLEAN" } },
            ],
          },
        },
        editability: { mode: "EDITABLE" },
        validation: {},
      },
      {
        fieldId: "f-4",
        key: "estimatedCost",
        label: "Estimated Cost",
        order: 4,
        type: { type: "DECIMAL" },
        sensitive: false,
        requirement: { mode: "ALWAYS" },
        visibility: { mode: "ALWAYS" },
        editability: { mode: "EDITABLE" },
        validation: { minimum: 10 },
      },
      {
        fieldId: "f-5",
        key: "apiKeySecret",
        label: "API Access Key",
        order: 5,
        type: { type: "STRING" },
        sensitive: true,
        requirement: { mode: "NEVER" },
        visibility: { mode: "ALWAYS" },
        editability: { mode: "EDITABLE" },
        validation: {},
      },
    ],
  },
};

describe("DynamicTicketForm Component", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders canonical form controls and honors sensitive masking", () => {
    render(
      <DynamicTicketForm
        initialSchema={INITIAL_SCHEMA}
        requestTypeKey="laptop_procurement"
      />,
    );

    expect(
      screen.getByLabelText(/Lý do yêu cầu/i),
    ).toBeInTheDocument();
    expect(screen.getByTestId("field-input-isUrgent")).toBeInTheDocument();
    expect(screen.getByTestId("form-field-isUrgent")).toHaveTextContent(
      "Yêu cầu này có gấp không?",
    );
    expect(screen.getByLabelText(/Chi phí dự kiến/i)).toBeInTheDocument();
    expect(screen.getByTestId("field-input-apiKeySecret")).toBeInTheDocument();
    expect(screen.getByText("Khóa truy cập API")).toBeInTheDocument();

    // Verify sensitive field starts masked as password
    const secretInput = screen.getByTestId("field-input-apiKeySecret");
    expect(secretInput).toHaveAttribute("type", "password");

    // Toggle reveal
    const toggleBtn = screen.getByTestId("toggle-sensitive-apiKeySecret");
    fireEvent.click(toggleBtn);
    expect(secretInput).toHaveAttribute("type", "text");
  });

  it("dynamically shows and hides conditional fields based on expression evaluation", () => {
    render(
      <DynamicTicketForm
        initialSchema={INITIAL_SCHEMA}
        requestTypeKey="laptop_procurement"
      />,
    );

    // Initially isUrgent is false, so urgencyReason should not be visible
    expect(
      screen.queryByLabelText(/Lý do yêu cầu gấp/i),
    ).not.toBeInTheDocument();

    // Toggle isUrgent to true
    const urgentCheckbox = screen.getByTestId("field-input-isUrgent");
    fireEvent.click(urgentCheckbox);

    // Now urgencyReason must be visible
    expect(screen.getByLabelText(/Lý do yêu cầu gấp/i)).toBeInTheDocument();

    // Toggle back to false
    fireEvent.click(urgentCheckbox);
    expect(
      screen.queryByLabelText(/Lý do yêu cầu gấp/i),
    ).not.toBeInTheDocument();
  });

  it("validates required fields before allowing submission", async () => {
    render(
      <DynamicTicketForm
        initialSchema={INITIAL_SCHEMA}
        requestTypeKey="laptop_procurement"
      />,
    );

    // Submit empty form
    const submitBtn = screen.getByTestId("submit-ticket-button");
    fireEvent.click(submitBtn);

    await waitFor(() => {
      expect(
        screen.getByText("Lý do yêu cầu là bắt buộc"),
      ).toBeInTheDocument();
      expect(
        screen.getByText("Chi phí dự kiến là bắt buộc"),
      ).toBeInTheDocument();
    });
  });

  it("submits the categoryKey contract atomically without the legacy draft selector", async () => {
    const create = vi.spyOn(api, "createCategoryTicket").mockResolvedValue({
      ticketId: "ticket-v241",
      eventId: "event-v241",
      categoryVersionId: "category-v1",
      formSubmissionId: "submission-v1",
      workflowVersionId: "workflow-v7",
      inputs: { amount: 25 },
      businessState: "SUBMITTED",
      createdAt: new Date().toISOString(),
    });
    const legacyDraft = vi.spyOn(api, "createTicketDraft");
    render(
      <DynamicTicketForm
        initialSchema={{
          ...INITIAL_SCHEMA,
          categoryKey: "PURCHASE",
          categoryVersionId: "category-v1",
          categoryChecksum: "category-sum",
          formVersionId: "form-v4",
          mappingChecksum: "mapping-sum",
        }}
        requestTypeKey="PURCHASE"
      />,
    );
    fireEvent.change(screen.getByTestId("field-input-justification"), {
      target: { value: "Purchase equipment for the platform team" },
    });
    fireEvent.change(screen.getByTestId("field-input-estimatedCost"), {
      target: { value: "25" },
    });
    fireEvent.click(screen.getByTestId("submit-ticket-button"));
    await waitFor(() =>
      expect(create).toHaveBeenCalledWith(
        "PURCHASE",
        expect.objectContaining({ categoryVersionId: "category-v1" }),
        expect.objectContaining({
          justification: "Purchase equipment for the platform team",
          estimatedCost: 25,
        }),
      ),
    );
    expect(legacyDraft).not.toHaveBeenCalled();
    expect(mockPush).toHaveBeenCalledWith("/tickets/ticket-v241");
  });

  it("handles concurrent publish schema mismatch (FORM_SCHEMA_CHANGED) by reloading schema and preserving values", async () => {
    // Fill form validly
    vi.spyOn(api, "createTicketDraft").mockResolvedValue({
      ticket: {
        id: "ticket-100",
        requestTypeId: "req-type-1",
        creatorId: "user-1",
        status: "DRAFT",
        dataJson: {},
        dataRevision: 0,
        currentRevisionId: null,
        lockVersion: 1,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
      revisions: [],
      subjects: [],
    });

    // Mock submit rejecting with FORM_SCHEMA_CHANGED
    vi.spyOn(api, "submitTicket").mockRejectedValue(
      new ApiRequestError(400, {
        code: "FORM_SCHEMA_CHANGED",
        message:
          "Form schema has changed since it was loaded. Please reload and review the form.",
      }),
    );

    // Mock updated schema on reload
    const UPDATED_SCHEMA: CreateSchemaResponse = {
      ...INITIAL_SCHEMA,
      sourceWorkflowVersionId: "wf-ver-2",
      formSchemaVersion: 2,
      formSchemaChecksum: "checksum-v2",
      ticketFormSchema: {
        ...INITIAL_SCHEMA.ticketFormSchema,
        fields: [
          ...INITIAL_SCHEMA.ticketFormSchema.fields,
          {
            fieldId: "f-new",
            key: "departmentCode",
            label: "Department Code",
            order: 6,
            type: { type: "STRING" },
            sensitive: false,
            requirement: { mode: "ALWAYS" },
            visibility: { mode: "ALWAYS" },
            editability: { mode: "EDITABLE" },
            validation: {},
          },
        ],
      },
    };
    const fetchSchemaSpy = vi
      .spyOn(api, "fetchCreateSchema")
      .mockResolvedValue(UPDATED_SCHEMA);

    render(
      <DynamicTicketForm
        initialSchema={INITIAL_SCHEMA}
        requestTypeKey="laptop_procurement"
      />,
    );

    // Fill valid data
    fireEvent.change(screen.getByTestId("field-input-justification"), {
      target: { value: "Upgrading development workstation for Spring Boot" },
    });
    fireEvent.change(screen.getByTestId("field-input-estimatedCost"), {
      target: { value: "2500" },
    });

    // Submit
    fireEvent.click(screen.getByTestId("submit-ticket-button"));

    // Verify mismatch banner appears
    await waitFor(() => {
      expect(screen.getByTestId("schema-mismatch-banner")).toBeInTheDocument();
      expect(screen.getByText("Biểu mẫu đã được cập nhật")).toBeInTheDocument();
    });

    // Click Reload Schema
    const reloadBtn = screen.getByTestId("reload-schema-button");
    fireEvent.click(reloadBtn);

    await waitFor(() => {
      expect(fetchSchemaSpy).toHaveBeenCalledWith("laptop_procurement");
      // Verify newly required field is present and highlighted
      expect(screen.getByLabelText(/Department Code/i)).toBeInTheDocument();
      expect(
        screen.getByTestId("newly-required-badge-departmentCode"),
      ).toBeInTheDocument();
      // Verify previous values are preserved
      expect(screen.getByTestId("field-input-justification")).toHaveValue(
        "Upgrading development workstation for Spring Boot",
      );
      expect(screen.getByTestId("field-input-estimatedCost")).toHaveValue(2500);
    });
  });
});
