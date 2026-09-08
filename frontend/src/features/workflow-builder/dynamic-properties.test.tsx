import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { DynamicNodeProperties } from "./components/dynamic-node-properties";
import type { BuilderNode } from "./types";

describe("DynamicNodeProperties Component", () => {
  it("renders schema version and approval modular tabs", () => {
    const approvalNode: BuilderNode = {
      id: "node-approval-1",
      type: "workflowNode",
      position: { x: 100, y: 100 },
      data: {
        key: "manager_approval",
        label: "Manager Approval",
        nodeType: "APPROVAL",
        outputPorts: ["APPROVED", "REJECTED"],
        config: {
          titleSnapshot: "Review Expense Request",
          priority: 80,
          formKey: "expense_form",
          allowedActions: ["APPROVED", "REJECTED"],
        },
      },
    };

    const updateSpy = vi.fn();
    render(
      <DynamicNodeProperties node={approvalNode} onUpdateConfig={updateSpy} />,
    );

    // Displays configSchemaVersion badge
    expect(screen.getByTestId("config-schema-version-badge")).toHaveTextContent(
      "Schema v1",
    );

    // Modular tabs for Approval panel
    expect(screen.getByTestId("approval-tab-general")).toBeInTheDocument();
    expect(screen.getByTestId("approval-tab-assignee")).toBeInTheDocument();
    expect(screen.getByTestId("approval-tab-taskGen")).toBeInTheDocument();
    expect(screen.getByTestId("approval-tab-form")).toBeInTheDocument();
    expect(screen.getByTestId("approval-tab-decision")).toBeInTheDocument();
    expect(screen.getByTestId("approval-tab-sla")).toBeInTheDocument();
    expect(screen.getByTestId("approval-tab-failure")).toBeInTheDocument();

    // General tab values
    expect(screen.getByTestId("input-titleSnapshot")).toHaveValue(
      "Review Expense Request",
    );
    expect(screen.getByTestId("input-priority")).toHaveValue(80);

    // Switch to Form tab
    fireEvent.click(screen.getByTestId("approval-tab-form"));
    expect(screen.getByTestId("input-formKey")).toHaveValue("expense_form");

    // Switch to Decision tab
    fireEvent.click(screen.getByTestId("approval-tab-decision"));
    expect(screen.getByTestId("allowed-actions-group")).toBeInTheDocument();
  });

  it("renders system action panel with connector, action version, and retry", () => {
    const systemActionNode: BuilderNode = {
      id: "node-sys-1",
      type: "workflowNode",
      position: { x: 100, y: 100 },
      data: {
        key: "slack_notify",
        label: "Slack Notify",
        nodeType: "SYSTEM_ACTION",
        outputPorts: ["SUCCESS", "ERROR"],
        config: {
          connectorKey: "slack",
          actionKey: "send_message",
          actionVersion: 2,
        },
      },
    };

    const updateSpy = vi.fn();
    render(
      <DynamicNodeProperties
        node={systemActionNode}
        onUpdateConfig={updateSpy}
      />,
    );

    expect(screen.getByTestId("system-action-tab-connector")).toBeInTheDocument();
    expect(screen.getByTestId("system-action-tab-actionVersion")).toBeInTheDocument();
    expect(screen.getByTestId("system-action-tab-retry")).toBeInTheDocument();

    expect(screen.getByTestId("input-connectorKey")).toHaveValue("slack");

    // Switch to Action Version tab
    fireEvent.click(screen.getByTestId("system-action-tab-actionVersion"));
    expect(screen.getByTestId("input-actionKey")).toHaveValue("send_message");
    expect(screen.getByTestId("input-actionVersion")).toHaveValue(2);
  });

  it("renders join panel with strict ALL/ANY policy and scope", () => {
    const joinNode: BuilderNode = {
      id: "node-join-1",
      type: "workflowNode",
      position: { x: 100, y: 100 },
      data: {
        key: "sync_join",
        label: "Sync Join",
        nodeType: "JOIN",
        outputPorts: ["DEFAULT"],
        config: {
          policy: "ALL",
          joinScopeId: "scope-branch-1",
          remainingBranchPolicy: "CANCEL_REMAINING",
        },
      },
    };

    render(
      <DynamicNodeProperties node={joinNode} onUpdateConfig={vi.fn()} />,
    );

    expect(screen.getByTestId("select-join-policy")).toHaveValue("ALL");

    // Switch to Scope tab
    fireEvent.click(screen.getByTestId("join-tab-scope"));
    expect(screen.getByTestId("input-joinScopeId")).toHaveValue(
      "scope-branch-1",
    );

    // Switch to Remaining Branch tab
    fireEvent.click(screen.getByTestId("join-tab-remaining"));
    expect(screen.getByTestId("select-remaining-branch-policy")).toHaveValue(
      "CANCEL_REMAINING",
    );
  });

  it("detects unknown properties and allows pruning to enforce strict schema", () => {
    const nodeWithUnknownProps: BuilderNode = {
      id: "node-subwf-1",
      type: "workflowNode",
      position: { x: 100, y: 100 },
      data: {
        key: "sub_onboarding",
        label: "Sub Onboarding",
        nodeType: "SUB_WORKFLOW",
        outputPorts: ["COMPLETED", "FAILED"],
        config: {
          childWorkflowDefinitionKey: "employee_onboarding",
          executionMode: "WAIT_FOR_COMPLETION",
          // Illegal undeclared properties:
          unknownCustomField: "hack",
          legacyModeFlag: true,
        },
      },
    };

    const updateSpy = vi.fn();
    render(
      <DynamicNodeProperties
        node={nodeWithUnknownProps}
        onUpdateConfig={updateSpy}
      />,
    );

    // Verify warning is displayed
    expect(screen.getByTestId("unknown-properties-alert")).toBeInTheDocument();
    expect(screen.getByText("unknownCustomField")).toBeInTheDocument();
    expect(screen.getByText("legacyModeFlag")).toBeInTheDocument();

    // Click Prune Undeclared
    const pruneBtn = screen.getByTestId("prune-unknown-properties-btn");
    fireEvent.click(pruneBtn);

    // Verify callback was called without the unknown properties
    expect(updateSpy).toHaveBeenCalledWith({
      childWorkflowDefinitionKey: "employee_onboarding",
      executionMode: "WAIT_FOR_COMPLETION",
    });
  });
});
