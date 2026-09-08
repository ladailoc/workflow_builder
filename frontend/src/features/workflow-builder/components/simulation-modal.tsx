"use client";

import { useId, useState } from "react";
import type { BuilderEdge, BuilderNode } from "../types";

interface SimulationStep {
  stepIndex: number;
  nodeId: string;
  nodeLabel: string;
  nodeType: string;
  evaluatedRoute?: string;
  participantPreview?: string;
  fanOutCount?: number;
  joinBehavior?: string;
  subWorkflowMappings?: Record<string, string>;
  warnings?: string[];
}

interface SimulationModalProps {
  isOpen: boolean;
  nodes: BuilderNode[];
  edges: BuilderEdge[];
  onClose: () => void;
}

const DEFAULT_MOCK_CONTEXT = JSON.stringify(
  {
    totalAmount: 7500,
    departmentId: "FINANCE",
    category: "CAPEX",
    items: ["ITEM-101", "ITEM-102", "ITEM-103"],
    creator: {
      id: "emp-1001",
      name: "Alice Submitter",
      managerId: "mgr-5001",
      managerName: "Bob Director",
      deptHeadId: "head-9001",
    },
  },
  null,
  2,
);

export function SimulationModal({
  isOpen,
  nodes,
  edges,
  onClose,
}: SimulationModalProps) {
  const [mockContextJson, setMockContextJson] = useState(DEFAULT_MOCK_CONTEXT);
  const [contextError, setContextError] = useState<string | null>(null);

  // Simulation execution steps
  const [steps, setSteps] = useState<SimulationStep[]>([]);
  const [currentStepIndex, setCurrentStepIndex] = useState<number>(0);
  const [isRunning, setIsRunning] = useState(false);
  const formHtmlId = useId();

  if (!isOpen) return null;

  // Purely in-memory dry-run simulator (side-effect-free)
  const computeSimulationTrace = (context: Record<string, unknown>): SimulationStep[] => {
    const trace: SimulationStep[] = [];
    const startNode = nodes.find((n) => n.data.nodeType === "START");
    if (!startNode) return trace;

    let currentNode: BuilderNode | undefined = startNode;
    let stepCount = 0;
    const visited = new Set<string>();

    while (currentNode && stepCount < 30) {
      visited.add(currentNode.id);
      const config = currentNode.data.config || {};
      const nodeType = currentNode.data.nodeType;

      const step: SimulationStep = {
        stepIndex: stepCount,
        nodeId: currentNode.id,
        nodeLabel: currentNode.data.label,
        nodeType,
      };

      // 1. Participant resolution preview
      if (nodeType === "APPROVAL" || nodeType === "REVIEW") {
        const participant = config.participant as Record<string, unknown> | undefined;
        const pType = String(participant?.type || "MANAGER_OF");
        if (pType === "MANAGER_OF") {
          const creator = context.creator as Record<string, unknown> | undefined;
          step.participantPreview = `Resolved Manager: ${creator?.managerName || "Bob Director"} (${creator?.managerId || "mgr-5001"})`;
        } else if (pType === "FIXED_USER") {
          step.participantPreview = `Resolved Fixed User: ${participant?.userId || "user-specified"}`;
        } else if (pType === "HEAD_OF_UNIT") {
          step.participantPreview = `Resolved Department Head: head-9001`;
        } else {
          step.participantPreview = `Resolved Creator: Alice Submitter`;
        }

        // Multi-instance fan out count
        if (config.multiInstance) {
          const items = Array.isArray(context.items) ? context.items : [];
          step.fanOutCount = items.length;
        }
      }

      // 2. Join arrival and continuation
      if (nodeType === "JOIN") {
        const policy = String(config.policy || "ALL");
        const incomingEdges = edges.filter((e) => e.target === currentNode?.id);
        step.joinBehavior = `Policy: ${policy} • Arriving branches: ${incomingEdges.length} • Activates downstream immediately.`;
      }

      // 3. SubWorkflow parameter mapping
      if (nodeType === "SUB_WORKFLOW") {
        step.subWorkflowMappings = {
          departmentId: String(context.departmentId ?? "FINANCE"),
          totalAmount: String(context.totalAmount ?? 0),
        };
      }

      // 4. Determine next step by evaluating outgoing edges
      const outEdges = edges.filter((e) => e.source === currentNode?.id);

      if (outEdges.length === 0 || nodeType === "END") {
        trace.push(step);
        break;
      }

      let selectedEdge = outEdges[0];

      // Simulated condition evaluation
      if (outEdges.length > 1) {
        const amount = Number(context.totalAmount ?? 0);
        const condEdge = outEdges.find((e) => {
          const cond = String(e.data?.condition || e.label || "");
          return cond.includes(">") && amount > 5000;
        });
        if (condEdge) {
          selectedEdge = condEdge;
          step.evaluatedRoute = `Condition matched (${condEdge.label ? String(condEdge.label) : "Over 5000 USD"})`;
        } else {
          const defaultEdge = outEdges.find((e) => e.data?.isDefault) || outEdges[0];
          selectedEdge = defaultEdge;
          step.evaluatedRoute = `Default fallback route: ${defaultEdge.label ? String(defaultEdge.label) : "Default"}`;
          step.warnings = ["No specific rule condition matched; took default fallback transition."];
        }
      } else {
        step.evaluatedRoute = selectedEdge.label ? String(selectedEdge.label) : "Direct transition";
      }

      trace.push(step);

      // Advance
      const nextNode = nodes.find((n) => n.id === selectedEdge.target);
      if (nextNode && visited.has(nextNode.id) && stepCount > 5) {
        step.warnings = [...(step.warnings || []), "Loop detected in path; stopping simulation."];
        break;
      }
      currentNode = nextNode;
      stepCount++;
    }

    return trace;
  };

  const handleRunSimulation = () => {
    try {
      const parsed = JSON.parse(mockContextJson);
      setContextError(null);
      const trace = computeSimulationTrace(parsed);
      setSteps(trace);
      setCurrentStepIndex(trace.length - 1);
      setIsRunning(true);
    } catch {
      setContextError("Invalid JSON in mock context payload.");
    }
  };

  const handleStepForward = () => {
    if (currentStepIndex < steps.length - 1) {
      setCurrentStepIndex((prev) => prev + 1);
    }
  };

  const handleStepBackward = () => {
    if (currentStepIndex > 0) {
      setCurrentStepIndex((prev) => prev - 1);
    }
  };

  const handleReset = () => {
    setSteps([]);
    setCurrentStepIndex(0);
    setIsRunning(false);
  };

  const visibleSteps = steps.slice(0, currentStepIndex + 1);
  const activeStep = steps[currentStepIndex];

  return (
    <div
      role="dialog"
      aria-modal="true"
      data-testid="simulation-modal"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-xs p-4"
    >
      <div className="w-full max-w-4xl rounded-xl bg-white p-6 shadow-2xl space-y-4 max-h-[90vh] flex flex-col animate-scale-in">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-200 pb-3">
          <div>
            <div className="flex items-center gap-2">
              <h3 className="text-sm font-bold text-slate-900">
                Design-Time Simulation Sandbox
              </h3>
              <span className="rounded bg-emerald-100 px-2 py-0.5 text-[10px] font-bold text-emerald-800">
                Side-Effect Free
              </span>
            </div>
            <p className="text-xs text-slate-500">
              Simulate graph traversal, participant resolution, and condition routing in-memory without saving or executing external services.
            </p>
          </div>
          <button
            type="button"
            data-testid="close-simulation-modal-btn"
            onClick={onClose}
            className="rounded p-1 text-slate-400 hover:text-slate-600"
          >
            ✕
          </button>
        </div>

        {/* Body Content */}
        <div className="grid grid-cols-5 gap-4 flex-1 overflow-hidden">
          {/* Left Column: Mock Input Context */}
          <div className="col-span-2 flex flex-col space-y-2 border-r border-slate-200 pr-3">
            <label
              htmlFor={`${formHtmlId}-mockContext`}
              className="text-xs font-bold text-slate-800 flex items-center justify-between"
            >
              <span>Mock Input Context</span>
              <button
                type="button"
                data-testid="reset-mock-context-btn"
                onClick={() => setMockContextJson(DEFAULT_MOCK_CONTEXT)}
                className="text-[10px] text-blue-600 hover:underline"
              >
                Reset Default
              </button>
            </label>
            <textarea
              id={`${formHtmlId}-mockContext`}
              rows={12}
              data-testid="textarea-mock-context"
              value={mockContextJson}
              onChange={(e) => setMockContextJson(e.target.value)}
              className="flex-1 w-full rounded-lg border border-slate-300 p-2 font-mono text-[11px] text-slate-800"
            />
            {contextError && (
              <p className="text-xs text-rose-600 font-medium">{contextError}</p>
            )}

            <div className="pt-2 flex items-center gap-2">
              <button
                type="button"
                data-testid="btn-run-simulation"
                onClick={handleRunSimulation}
                className="flex-1 rounded-lg bg-blue-600 py-2 text-xs font-semibold text-white hover:bg-blue-700 shadow-xs"
              >
                Run to Completion
              </button>
              {isRunning && (
                <button
                  type="button"
                  data-testid="btn-reset-simulation"
                  onClick={handleReset}
                  className="rounded-lg border border-slate-300 px-3 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50"
                >
                  Reset
                </button>
              )}
            </div>
          </div>

          {/* Right Column: Execution Timeline & Trace */}
          <div className="col-span-3 flex flex-col space-y-3 overflow-hidden">
            <div className="flex items-center justify-between border-b border-slate-100 pb-2">
              <span className="text-xs font-bold text-slate-800">
                Simulated Execution Trace ({visibleSteps.length} / {steps.length} Steps)
              </span>
              {isRunning && (
                <div className="flex items-center gap-1">
                  <button
                    type="button"
                    data-testid="btn-step-backward"
                    disabled={currentStepIndex === 0}
                    onClick={handleStepBackward}
                    className="rounded border border-slate-200 px-2 py-1 text-xs font-semibold text-slate-700 disabled:opacity-30"
                  >
                    ◀ Step
                  </button>
                  <button
                    type="button"
                    data-testid="btn-step-forward"
                    disabled={currentStepIndex >= steps.length - 1}
                    onClick={handleStepForward}
                    className="rounded border border-slate-200 px-2 py-1 text-xs font-semibold text-slate-700 disabled:opacity-30"
                  >
                    Step ▶
                  </button>
                </div>
              )}
            </div>

            {/* Trace List */}
            <div
              data-testid="simulation-trace-list"
              className="flex-1 overflow-y-auto space-y-2 pr-1"
            >
              {steps.length === 0 ? (
                <div className="rounded-lg border border-dashed border-slate-200 p-8 text-center text-xs text-slate-400">
                  Click &quot;Run to Completion&quot; to dry-run this workflow with mock input.
                </div>
              ) : (
                visibleSteps.map((s, idx) => (
                  <div
                    key={`${s.nodeId}-${idx}`}
                    data-testid={`simulation-step-${idx}`}
                    className={`rounded-lg border p-3 text-xs space-y-1.5 transition-all ${
                      idx === currentStepIndex
                        ? "border-blue-500 bg-blue-50/50 ring-1 ring-blue-400"
                        : "border-slate-200 bg-white"
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <span className="flex h-5 w-5 items-center justify-center rounded-full bg-slate-100 font-mono text-[10px] font-bold text-slate-700">
                          {idx + 1}
                        </span>
                        <span className="font-bold text-slate-800">
                          {s.nodeLabel}
                        </span>
                        <span className="rounded bg-slate-100 px-1.5 py-0.2 font-mono text-[10px] text-slate-600">
                          {s.nodeType}
                        </span>
                      </div>
                      {s.evaluatedRoute && (
                        <span className="rounded bg-emerald-50 px-2 py-0.5 text-[10px] font-semibold text-emerald-700">
                          Route: {s.evaluatedRoute}
                        </span>
                      )}
                    </div>

                    {/* Participant Resolution */}
                    {s.participantPreview && (
                      <p
                        data-testid="sim-participant-preview"
                        className="text-[11px] text-indigo-700 font-medium"
                      >
                        👤 {s.participantPreview}
                      </p>
                    )}

                    {/* Fan-Out Item Count */}
                    {s.fanOutCount !== undefined && (
                      <p
                        data-testid="sim-fanout-count"
                        className="text-[11px] text-amber-700 font-medium"
                      >
                        🔀 Multi-Instance: Spawns {s.fanOutCount} parallel tasks
                      </p>
                    )}

                    {/* Join Synchronization */}
                    {s.joinBehavior && (
                      <p
                        data-testid="sim-join-behavior"
                        className="text-[11px] text-purple-700 font-medium"
                      >
                        ⚡ Join: {s.joinBehavior}
                      </p>
                    )}

                    {/* SubWorkflow Mappings */}
                    {s.subWorkflowMappings && (
                      <div
                        data-testid="sim-subworkflow-mappings"
                        className="rounded bg-slate-50 p-1.5 text-[10px] font-mono text-slate-600"
                      >
                        Mappings: {JSON.stringify(s.subWorkflowMappings)}
                      </div>
                    )}

                    {/* Warnings */}
                    {s.warnings && s.warnings.length > 0 && (
                      <div
                        data-testid="sim-warnings-alert"
                        className="rounded bg-amber-50 p-1.5 text-[11px] text-amber-800"
                      >
                        ⚠️ {s.warnings.join(" ")}
                      </div>
                    )}
                  </div>
                ))
              )}
            </div>

            {/* Current Active Step Details */}
            {activeStep && (
              <div className="rounded-lg border border-slate-200 bg-slate-50 p-2.5 text-[11px] text-slate-600 flex items-center justify-between">
                <span>
                  Active Node: <strong>{activeStep.nodeLabel}</strong> ({activeStep.nodeId})
                </span>
                <span className="font-mono text-[10px] text-slate-400">
                  Step {currentStepIndex + 1} of {steps.length}
                </span>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
