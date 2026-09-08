import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { FormBuilder } from "./components/form-builder";
import { ParticipantBuilder } from "./components/participant-builder";
import { findFieldDependencies } from "./utils/field-dependency";
import {
  compileParticipantConfig,
  decompileParticipantConfig,
  explainResolutionBehavior,
} from "./utils/participant-compiler";
import type { BuilderEdge, BuilderNode } from "./types";
import type { FormSchema } from "./form-types";

describe("Prompt 55: Form Builder & Participant Builder", () => {
  describe("findFieldDependencies", () => {
    const mockNodes: BuilderNode[] = [
      {
        id: "node-approval-1",
        type: "workflowNode",
        position: { x: 0, y: 0 },
        data: {
          key: "manager_approval",
          label: "Manager Approval",
          nodeType: "APPROVAL",
          outputPorts: ["APPROVED", "REJECTED"],
          config: {
            titleSnapshot: "Approval Request for ${expenseAmount} USD",
            participant: {
              type: "ITEM_USER",
              field: "designatedApprover",
            },
          },
        },
      },
      {
        id: "node-cond-1",
        type: "workflowNode",
        position: { x: 100, y: 100 },
        data: {
          key: "amount_check",
          label: "High Value Check",
          nodeType: "CONDITION",
          outputPorts: ["TRUE", "FALSE"],
          config: {
            expression: {
              operator: "GT",
              operands: ["expenseAmount", 5000],
            },
          },
        },
      },
      {
        id: "node-subwf-1",
        type: "workflowNode",
        position: { x: 200, y: 200 },
        data: {
          key: "child_proc",
          label: "Child Process",
          nodeType: "SUB_WORKFLOW",
          outputPorts: ["COMPLETED"],
          config: {
            inputMappings: [
              { source: "departmentCode", target: "childDept" },
            ],
          },
        },
      },
    ];

    const mockEdges: BuilderEdge[] = [
      {
        id: "edge-1",
        source: "node-cond-1",
        target: "node-approval-1",
        label: "payload.expenseAmount > 5000",
        data: {
          condition: "payload.expenseAmount > 5000",
        },
      },
    ];

    it("detects transition condition references on edges and condition nodes", () => {
      const deps = findFieldDependencies("expenseAmount", mockNodes, mockEdges);
      const edgeDep = deps.find((d) => d.type === "TRANSITION_CONDITION" && d.targetId === "edge-1");
      const nodeCondDep = deps.find(
        (d) => d.type === "TRANSITION_CONDITION" && d.targetId === "node-cond-1",
      );

      expect(edgeDep).toBeDefined();
      expect(nodeCondDep).toBeDefined();
    });

    it("detects participant expressions", () => {
      const deps = findFieldDependencies("designatedApprover", mockNodes, mockEdges);
      const partDep = deps.find((d) => d.type === "PARTICIPANT_EXPRESSION");

      expect(partDep).toBeDefined();
      expect(partDep?.targetId).toBe("node-approval-1");
    });

    it("detects title snapshot templates", () => {
      const deps = findFieldDependencies("expenseAmount", mockNodes, mockEdges);
      const titleDep = deps.find((d) => d.type === "TITLE_TEMPLATE");

      expect(titleDep).toBeDefined();
      expect(titleDep?.detail).toContain("${expenseAmount}");
    });

    it("detects child workflow mappings", () => {
      const deps = findFieldDependencies("departmentCode", mockNodes, mockEdges);
      const subwfDep = deps.find((d) => d.type === "CHILD_WORKFLOW_MAPPING");

      expect(subwfDep).toBeDefined();
      expect(subwfDep?.targetId).toBe("node-subwf-1");
    });

    it("returns empty array for an unreferenced field", () => {
      const deps = findFieldDependencies("unreferencedField", mockNodes, mockEdges);
      expect(deps).toHaveLength(0);
    });
  });

  describe("FormBuilder Component", () => {
    const initialSchema: FormSchema = {
      fields: [
        { key: "expenseAmount", label: "Expense Amount", type: "DECIMAL", required: true },
        { key: "notes", label: "Notes", type: "STRING", required: false },
      ],
    };

    const mockNodes: BuilderNode[] = [
      {
        id: "node-approval",
        type: "workflowNode",
        position: { x: 0, y: 0 },
        data: {
          key: "appr",
          label: "Approval Step",
          nodeType: "APPROVAL",
          outputPorts: ["APPROVED"],
          config: {
            titleSnapshot: "Review ${expenseAmount}",
          },
        },
      },
    ];

    it("renders field list with type badges and reordering controls", () => {
      const changeSpy = vi.fn();
      render(<FormBuilder schema={initialSchema} onChange={changeSpy} />);

      expect(screen.getByText("expenseAmount")).toBeInTheDocument();
      expect(screen.getByText("DECIMAL")).toBeInTheDocument();
      expect(screen.getByText("notes")).toBeInTheDocument();

      // Test reordering move down
      fireEvent.click(screen.getByTestId("field-move-down-expenseAmount"));
      expect(changeSpy).toHaveBeenCalledWith({
        fields: [initialSchema.fields[1], initialSchema.fields[0]],
      });
    });

    it("alerts with FieldDependencyModal when attempting to delete an in-use field", () => {
      const changeSpy = vi.fn();
      render(
        <FormBuilder
          schema={initialSchema}
          onChange={changeSpy}
          nodes={mockNodes}
        />,
      );

      // Attempt delete on 'expenseAmount' which is used in titleSnapshot
      fireEvent.click(screen.getByTestId("delete-field-expenseAmount"));

      // Dependency modal should open
      expect(screen.getByTestId("field-dependency-modal")).toBeInTheDocument();
      expect(screen.getByText(/Breaking Change Detected/i)).toBeInTheDocument();
      expect(screen.getByTestId("dependency-item")).toHaveTextContent("TITLE TEMPLATE");

      // Clicking Force Delete confirms the action
      fireEvent.click(screen.getByTestId("confirm-dependency-action-btn"));
      expect(changeSpy).toHaveBeenCalledWith({
        fields: [initialSchema.fields[1]],
      });
    });

    it("allows immediate delete without modal when field has no dependencies", () => {
      const changeSpy = vi.fn();
      render(
        <FormBuilder
          schema={initialSchema}
          onChange={changeSpy}
          nodes={mockNodes}
        />,
      );

      // Delete 'notes' which has 0 dependencies
      fireEvent.click(screen.getByTestId("delete-field-notes"));
      expect(screen.queryByTestId("field-dependency-modal")).not.toBeInTheDocument();
      expect(changeSpy).toHaveBeenCalledWith({
        fields: [initialSchema.fields[0]],
      });
    });
  });

  describe("ParticipantBuilder Component & Compiler", () => {
    it("compiles friendly options to generic resolver primitives", () => {
      // 1. Creator's Manager -> MANAGER_OF depth 1
      const p1 = compileParticipantConfig({ kind: "CREATORS_MANAGER" });
      expect(p1.type).toBe("MANAGER_OF");
      expect(p1.depth).toBe(1);

      // 2. Manager N Levels Up -> MANAGER_OF depth N
      const p2 = compileParticipantConfig({ kind: "MANAGER_N_LEVELS_UP", depth: 3 });
      expect(p2.type).toBe("MANAGER_OF");
      expect(p2.depth).toBe(3);

      // 3. Department Head -> HEAD_OF_UNIT
      const p3 = compileParticipantConfig({ kind: "DEPARTMENT_HEAD" });
      expect(p3.type).toBe("HEAD_OF_UNIT");

      // 4. Fixed User -> FIXED_USER with userId
      const p4 = compileParticipantConfig({
        kind: "FIXED_USER",
        userId: "11111111-1111-1111-1111-111111111111",
      });
      expect(p4.type).toBe("FIXED_USER");
      expect(p4.userId).toBe("11111111-1111-1111-1111-111111111111");

      // 5. Creator -> CREATOR
      const p5 = compileParticipantConfig({ kind: "CREATOR" });
      expect(p5.type).toBe("CREATOR");

      // 6. Request Field -> ITEM_USER with field
      const p6 = compileParticipantConfig({ kind: "REQUEST_FIELD", fieldKey: "approverKey" });
      expect(p6.type).toBe("ITEM_USER");
      expect(p6.field).toBe("approverKey");
    });

    it("decompiles generic resolver primitives back into friendly state", () => {
      const d1 = decompileParticipantConfig({ type: "MANAGER_OF", depth: 1 });
      expect(d1.kind).toBe("CREATORS_MANAGER");

      const d2 = decompileParticipantConfig({ type: "MANAGER_OF", depth: 4 });
      expect(d2.kind).toBe("MANAGER_N_LEVELS_UP");
      expect(d2.depth).toBe(4);

      const d3 = decompileParticipantConfig({ type: "HEAD_OF_UNIT" });
      expect(d3.kind).toBe("DEPARTMENT_HEAD");
    });

    it("renders ParticipantBuilder UI with fallback chain and explanation", () => {
      const changeSpy = vi.fn();
      render(
        <ParticipantBuilder
          value={{
            type: "MANAGER_OF",
            depth: 1,
            cardinality: "MULTI",
            taskGenerationMode: "ONE_PER_PARTICIPANT",
            completionPolicy: "QUORUM",
            quorumCount: 3,
            fallbackChain: [{ type: "HEAD_OF_UNIT" }, { type: "CREATOR" }],
          }}
          onChange={changeSpy}
        />,
      );

      // Primary resolver
      expect(screen.getByTestId("select-primary-resolver")).toHaveValue("CREATORS_MANAGER");

      // Multi-instance task generation and quorum count
      expect(screen.getByTestId("select-task-generation-mode")).toHaveValue("ONE_PER_PARTICIPANT");
      expect(screen.getByTestId("select-completion-policy")).toHaveValue("QUORUM");
      expect(screen.getByTestId("input-quorum-count")).toHaveValue(3);

      // Fallback items
      expect(screen.getByTestId("fallback-item-0")).toBeInTheDocument();
      expect(screen.getByTestId("fallback-item-1")).toBeInTheDocument();

      // Runtime explanation card
      const explanation = screen.getByTestId("runtime-resolution-explanation");
      expect(explanation).toHaveTextContent("direct manager (depth 1)");
      expect(explanation).toHaveTextContent("evaluates fallback chain in order");
      expect(explanation).toHaveTextContent("QUORUM of at least 3");

      // Add another fallback
      fireEvent.click(screen.getByTestId("add-fallback-btn"));
      expect(changeSpy).toHaveBeenCalled();
    });

    it("generates clear resolution explanation for diverse policies", () => {
      const singleDesc = explainResolutionBehavior({
        kind: "DEPARTMENT_HEAD",
        cardinality: "SINGLE",
      });
      expect(singleDesc).toContain("department head");
      expect(singleDesc).toContain("Assigns a single task");

      const multiPercentageDesc = explainResolutionBehavior({
        kind: "ROLE",
        role: "FINANCE_APPROVER",
        cardinality: "MULTI",
        taskGenerationMode: "SINGLE_CLAIMABLE",
        completionPolicy: "PERCENTAGE",
        completionPercentage: 75,
      });
      expect(multiPercentageDesc).toContain("FINANCE_APPROVER");
      expect(multiPercentageDesc).toContain("SINGLE_CLAIMABLE");
      expect(multiPercentageDesc).toContain("at least 75%");
    });
  });
});
