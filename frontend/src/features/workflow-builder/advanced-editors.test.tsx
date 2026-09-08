import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { EdgeEditorModal } from "./components/edge-editor-modal";
import { ConditionExpressionEditor } from "./components/condition-expression-editor";
import { JoinEditor } from "./components/join-editor";
import { ReworkEditor } from "./components/rework-editor";
import { MultiInstanceEditor } from "./components/multi-instance-editor";
import {
  compileAtomicClause,
  compileConditionExpression,
  decompileConditionExpression,
} from "./utils/condition-compiler";
import type { BuilderEdge, BuilderNode } from "./types";

describe("Prompt 56: Advanced Graph Editors", () => {
  const mockNodes: BuilderNode[] = [
    {
      id: "node_start",
      type: "workflowNode",
      position: { x: 0, y: 0 },
      data: {
        key: "start_1",
        label: "Start Process",
        nodeType: "START",
        outputPorts: ["DEFAULT"],
        config: {},
      },
    },
    {
      id: "node_approval",
      type: "workflowNode",
      position: { x: 150, y: 150 },
      data: {
        key: "appr_1",
        label: "Manager Review",
        nodeType: "APPROVAL",
        outputPorts: ["APPROVED", "REJECTED"],
        config: {},
      },
    },
    {
      id: "node_end",
      type: "workflowNode",
      position: { x: 300, y: 300 },
      data: {
        key: "end_1",
        label: "End Process",
        nodeType: "END",
        outputPorts: [],
        config: {},
      },
    },
  ];

  const mockEdge: BuilderEdge = {
    id: "edge_start_appr",
    source: "node_start",
    target: "node_approval",
    sourceHandle: "DEFAULT",
    label: "Initial Transition",
    data: {
      priority: 10,
      isDefault: false,
      transitionType: "NORMAL",
    },
  };

  describe("Edge Editor", () => {
    it("renders edge transition properties and preserves destination only in edge", () => {
      const saveSpy = vi.fn();
      const closeSpy = vi.fn();

      render(
        <EdgeEditorModal
          isOpen={true}
          edge={mockEdge}
          nodes={mockNodes}
          onSave={saveSpy}
          onClose={closeSpy}
        />,
      );

      // Verify modal elements
      expect(screen.getByTestId("edge-editor-modal")).toBeInTheDocument();
      expect(screen.getByTestId("select-edge-source-handle")).toHaveValue("DEFAULT");
      expect(screen.getByTestId("select-edge-target-node")).toHaveValue("node_approval");
      expect(screen.getByTestId("select-edge-transition-type")).toHaveValue("NORMAL");
      expect(screen.getByTestId("input-edge-priority")).toHaveValue(10);
      expect(screen.getByTestId("input-edge-label")).toHaveValue("Initial Transition");

      // Switch transition type to REWORK
      fireEvent.change(screen.getByTestId("select-edge-transition-type"), {
        target: { value: "REWORK" },
      });
      expect(screen.getByTestId("edge-tab-rework")).toBeInTheDocument();

      // Change target destination to node_end
      fireEvent.change(screen.getByTestId("select-edge-target-node"), {
        target: { value: "node_end" },
      });

      // Save changes
      fireEvent.click(screen.getByTestId("btn-save-edge"));

      expect(saveSpy).toHaveBeenCalledTimes(1);
      const savedEdge = saveSpy.mock.calls[0][0] as BuilderEdge;
      expect(savedEdge.target).toBe("node_end");
      expect((savedEdge.data as Record<string, unknown>).transitionType).toBe("REWORK");

      // Destination strictly exists in Edge, not duplicated inside source node data or config
      expect(mockNodes[0].data.config.target).toBeUndefined();
      expect(mockNodes[0].data.config.destination).toBeUndefined();
    });
  });

  describe("Condition Expression Editor & Compiler", () => {
    it("compiles atomic clauses to typed safe AST with context variables", () => {
      const ast1 = compileAtomicClause({
        id: "c1",
        field: "payload.totalAmount",
        operator: "GT",
        value: "5000",
      });

      expect(ast1.operator).toBe("GT");
      expect(ast1.operands[0]).toEqual({ path: "payload.totalAmount" });
      expect(ast1.operands[1]).toEqual({ value: 5000, type: "INTEGER" });

      // Decimal
      const ast2 = compileAtomicClause({
        id: "c2",
        field: "event.taxRate",
        operator: "LTE",
        value: "15.5",
      });
      expect(ast2.operands[1]).toEqual({ value: 15.5, type: "DECIMAL" });

      // IS_NULL and NOT_NULL
      const astNull = compileAtomicClause({
        id: "c3",
        field: "payload.reviewerComment",
        operator: "IS_NULL",
        value: "",
      });
      expect(astNull.operator).toBe("IS_NULL");
      expect(astNull.operands).toEqual([{ path: "payload.reviewerComment" }]);

      const astNotNull = compileAtomicClause({
        id: "c4",
        field: "system.correlationId",
        operator: "NOT_NULL",
        value: "",
      });
      expect(astNotNull.operator).toBe("NOT");
    });

    it("compiles composite groups with AND, OR, and NOT logical operators", () => {
      const compiled = compileConditionExpression({
        id: "g1",
        logical: "OR",
        clauses: [
          {
            id: "c1",
            field: "payload.isVip",
            operator: "EQ",
            value: "true",
          },
          {
            id: "c2",
            field: "event.priority",
            operator: "GTE",
            value: "90",
          },
        ],
      });

      expect(compiled.operator).toBe("OR");
      expect(compiled.operands).toHaveLength(2);
    });

    it("decompiles AST back to UI group structure", () => {
      const ast = {
        operator: "AND",
        operands: [
          {
            operator: "GT",
            operands: [{ path: "payload.amount" }, { value: 200, type: "INTEGER" }],
          },
        ],
      };

      const uiGroup = decompileConditionExpression(ast);
      expect(uiGroup.logical).toBe("AND");
      expect(uiGroup.clauses).toHaveLength(1);
    });

    it("interacts with ConditionExpressionEditor UI controls", () => {
      const changeSpy = vi.fn();
      render(
        <ConditionExpressionEditor
          value={{
            operator: "AND",
            operands: [
              {
                operator: "GT",
                operands: [{ path: "payload.totalAmount" }, { value: 5000, type: "INTEGER" }],
              },
            ],
          }}
          onChange={changeSpy}
        />,
      );

      // Switch logical group to OR
      fireEvent.click(screen.getByTestId("btn-logical-or"));
      expect(changeSpy).toHaveBeenCalled();

      // Add a new clause
      fireEvent.click(screen.getByTestId("btn-add-clause"));
      expect(changeSpy).toHaveBeenCalled();
    });
  });

  describe("Join Editor Policy Restriction", () => {
    it("strictly enforces only ALL / ANY join semantics without unsupported N_OF_M", () => {
      const changeSpy = vi.fn();
      render(<JoinEditor onChange={changeSpy} />);

      const select = screen.getByTestId("select-join-execution-policy") as HTMLSelectElement;
      expect(select).toBeInTheDocument();

      const options = Array.from(select.options).map((opt) => opt.value);
      expect(options).toContain("ALL");
      expect(options).toContain("ANY");

      // Verify unsupported N_OF_M is strictly NOT offered in UI
      expect(options).not.toContain("N_OF_M");
      expect(screen.queryByText(/N_OF_M/i)).not.toBeInTheDocument();

      // Change policy to ANY
      fireEvent.change(select, { target: { value: "ANY" } });
      expect(changeSpy).toHaveBeenCalledWith(
        expect.objectContaining({ policy: "ANY" }),
      );

      // Update scope ID and remaining branch policy
      fireEvent.change(screen.getByTestId("input-join-scope-id"), {
        target: { value: "scope_split_1" },
      });
      expect(changeSpy).toHaveBeenCalledWith(
        expect.objectContaining({ joinScopeId: "scope_split_1" }),
      );

      fireEvent.change(screen.getByTestId("select-join-remaining-policy"), {
        target: { value: "AWAIT_COMPLETION" },
      });
      expect(changeSpy).toHaveBeenCalledWith(
        expect.objectContaining({ remainingBranchPolicy: "AWAIT_COMPLETION" }),
      );
    });
  });

  describe("Rework Editor", () => {
    it("configures rework scope, iteration limits, and exhaustion behaviors", () => {
      const changeSpy = vi.fn();
      render(
        <ReworkEditor
          availableNodes={mockNodes}
          value={{
            targetStepId: "node_approval",
            maxIterations: 3,
            exhaustionBehavior: "FAIL_EVENT",
            rollbackStrategy: "KEEP_CURRENT",
            multiInstanceScope: "CURRENT_ITEM",
          }}
          onChange={changeSpy}
        />,
      );

      expect(screen.getByTestId("select-rework-target-step")).toHaveValue("node_approval");
      expect(screen.getByTestId("input-max-rework-iterations")).toHaveValue(3);
      expect(screen.getByTestId("select-rework-exhaustion-behavior")).toHaveValue("FAIL_EVENT");
      expect(screen.getByTestId("select-rework-rollback-strategy")).toHaveValue("KEEP_CURRENT");
      expect(screen.getByTestId("select-rework-mi-scope")).toHaveValue("CURRENT_ITEM");

      // Change max iterations
      fireEvent.change(screen.getByTestId("input-max-rework-iterations"), {
        target: { value: "5" },
      });
      expect(changeSpy).toHaveBeenCalledWith(
        expect.objectContaining({ maxIterations: 5 }),
      );

      // Change multi-instance rework scope to WHOLE_NODE
      fireEvent.change(screen.getByTestId("select-rework-mi-scope"), {
        target: { value: "WHOLE_NODE" },
      });
      expect(changeSpy).toHaveBeenCalledWith(
        expect.objectContaining({ multiInstanceScope: "WHOLE_NODE" }),
      );

      // Change exhaustion behavior to ROUTE_ESCALATION
      fireEvent.change(screen.getByTestId("select-rework-exhaustion-behavior"), {
        target: { value: "ROUTE_ESCALATION" },
      });
      expect(changeSpy).toHaveBeenCalledWith(
        expect.objectContaining({ exhaustionBehavior: "ROUTE_ESCALATION" }),
      );
    });
  });

  describe("MultiInstanceEditor", () => {
    it("configures collection expression, item variable, and concurrency", () => {
      const changeSpy = vi.fn();
      render(
        <MultiInstanceEditor
          value={{
            collectionExpression: "payload.requisitions",
            itemVariable: "req",
            concurrency: "SEQUENTIAL",
            completionPolicy: "PERCENTAGE",
            completionPercentage: 80,
            remainingItemPolicy: "ALLOW_COMPLETION",
          }}
          onChange={changeSpy}
        />,
      );

      expect(screen.getByTestId("input-mi-collection-expression")).toHaveValue("payload.requisitions");
      expect(screen.getByTestId("input-mi-item-variable")).toHaveValue("req");
      expect(screen.getByTestId("select-mi-concurrency")).toHaveValue("SEQUENTIAL");
      expect(screen.getByTestId("select-mi-completion-policy")).toHaveValue("PERCENTAGE");
      expect(screen.getByTestId("input-mi-completion-percentage")).toHaveValue(80);
      expect(screen.getByTestId("select-mi-remaining-policy")).toHaveValue("ALLOW_COMPLETION");

      // Change concurrency to PARALLEL
      fireEvent.change(screen.getByTestId("select-mi-concurrency"), {
        target: { value: "PARALLEL" },
      });
      expect(changeSpy).toHaveBeenCalledWith(
        expect.objectContaining({ concurrency: "PARALLEL" }),
      );
    });
  });
});
