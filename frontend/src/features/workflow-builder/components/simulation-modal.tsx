"use client";

import { useId, useState } from "react";
import type { BuilderEdge, BuilderNode } from "../types";
import { apiPost } from "@/shared/api/client";

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
  workflowId?: string;
  versionId?: string;
  onClose: () => void;
}

interface BackendSimulationResult {
  transitions: Array<{
    sourceNodeKey: string;
    outputPort: string;
    targetNodeKey: string;
    priority: number;
    selected: boolean;
    edgeId: string;
  }>;
  participants: Array<{ nodeKey: string; resolverType: string; status: string }>;
  multiInstancePlans: Array<{
    nodeKey: string;
    collectionPath: string;
    itemVariable: string;
    plannedCount: number;
  }>;
  subWorkflows: Array<{
    nodeKey: string;
    childDefinitionKey: string;
    childKey: string;
    resolution: string;
    disposition: string;
  }>;
  warnings: string[];
  validationIssues: string[];
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
  workflowId,
  versionId,
  onClose,
}: SimulationModalProps) {
  const [mockContextJson, setMockContextJson] = useState(DEFAULT_MOCK_CONTEXT);
  const [contextError, setContextError] = useState<string | null>(null);

  // Simulation execution steps
  const [steps, setSteps] = useState<SimulationStep[]>([]);
  const [currentStepIndex, setCurrentStepIndex] = useState<number>(0);
  const [isRunning, setIsRunning] = useState(false);
  const [simulationError, setSimulationError] = useState<string | null>(null);
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
          step.participantPreview = `Quản lý được xác định: ${creator?.managerName || "Bob Director"} (${creator?.managerId || "mgr-5001"})`;
        } else if (pType === "FIXED_USER") {
          step.participantPreview = `Người dùng cố định: ${participant?.userId || "do người dùng chỉ định"}`;
        } else if (pType === "HEAD_OF_UNIT") {
          step.participantPreview = `Trưởng phòng ban được xác định: head-9001`;
        } else {
          step.participantPreview = `Người tạo được xác định: Alice Submitter`;
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
        step.joinBehavior = `Quy tắc: ${policy} • Nhánh đã đến: ${incomingEdges.length} • Kích hoạt bước tiếp theo ngay.`;
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
          step.evaluatedRoute = `Điều kiện phù hợp (${condEdge.label ? String(condEdge.label) : "Trên 5.000 USD"})`;
        } else {
          const defaultEdge = outEdges.find((e) => e.data?.isDefault) || outEdges[0];
          selectedEdge = defaultEdge;
          step.evaluatedRoute = `Tuyến mặc định: ${defaultEdge.label ? String(defaultEdge.label) : "Mặc định"}`;
          step.warnings = ["Không có điều kiện cụ thể phù hợp; đã dùng chuyển tiếp mặc định."];
        }
      } else {
        step.evaluatedRoute = selectedEdge.label ? String(selectedEdge.label) : "Chuyển tiếp trực tiếp";
      }

      trace.push(step);

      // Advance
      const nextNode = nodes.find((n) => n.id === selectedEdge.target);
      if (nextNode && visited.has(nextNode.id) && stepCount > 5) {
        step.warnings = [...(step.warnings || []), "Phát hiện vòng lặp; dừng mô phỏng."];
        break;
      }
      currentNode = nextNode;
      stepCount++;
    }

    return trace;
  };

  const applyBackendResult = (result: BackendSimulationResult) => {
    const participants = new Map(result.participants.map((item) => [item.nodeKey, item]));
    const fanOut = new Map(result.multiInstancePlans.map((item) => [item.nodeKey, item]));
    const subWorkflows = new Map(result.subWorkflows.map((item) => [item.nodeKey, item]));
    const visited = new Set<string>();
    const trace: SimulationStep[] = [];
    for (const transition of result.transitions.filter((item) => item.selected)) {
      if (visited.has(transition.sourceNodeKey)) continue;
      visited.add(transition.sourceNodeKey);
      const node = nodes.find(
        (item) => item.data.key === transition.sourceNodeKey || item.id === transition.sourceNodeKey,
      );
      if (!node) continue;
      const participant = participants.get(transition.sourceNodeKey);
      const plan = fanOut.get(transition.sourceNodeKey);
      const subWorkflow = subWorkflows.get(transition.sourceNodeKey);
      trace.push({
        stepIndex: trace.length,
        nodeId: node.id,
        nodeLabel: node.data.label,
        nodeType: node.data.nodeType,
        evaluatedRoute: `${transition.outputPort} → ${transition.targetNodeKey}`,
        participantPreview: participant
          ? `${participant.resolverType} (${participant.status})`
          : undefined,
        fanOutCount: plan?.plannedCount,
        subWorkflowMappings: subWorkflow
          ? { child: subWorkflow.childDefinitionKey, resolution: subWorkflow.resolution }
          : undefined,
        warnings: result.warnings,
      });
    }
    setSteps(trace);
    setCurrentStepIndex(Math.max(trace.length - 1, 0));
    setIsRunning(true);
  };

  const handleRunSimulation = async () => {
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(mockContextJson) as Record<string, unknown>;
    } catch {
      setContextError("Dữ liệu mô phỏng không đúng định dạng JSON.");
      return;
    }
    setContextError(null);
    setSimulationError(null);
    try {
      if (workflowId && versionId) {
        const result = await apiPost<BackendSimulationResult>(
          `/api/v1/workflows/${encodeURIComponent(workflowId)}/versions/${encodeURIComponent(versionId)}/simulate`,
          { ticketData: parsed, subjects: [] },
        );
        applyBackendResult(result);
      } else {
        const trace = computeSimulationTrace(parsed);
        setSteps(trace);
        setCurrentStepIndex(Math.max(trace.length - 1, 0));
        setIsRunning(true);
      }
    } catch {
      setSimulationError("Không thể chạy mô phỏng phía máy chủ. Kiểm tra dữ liệu và quyền truy cập.");
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
                Môi trường mô phỏng quy trình
              </h3>
              <span className="rounded bg-emerald-100 px-2 py-0.5 text-[10px] font-bold text-emerald-800">
                Không tác động dữ liệu thật
              </span>
            </div>
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
              <span>Dữ liệu đầu vào mô phỏng</span>
              <button
                type="button"
                data-testid="reset-mock-context-btn"
                onClick={() => setMockContextJson(DEFAULT_MOCK_CONTEXT)}
                className="text-[10px] text-blue-600 hover:underline"
              >
                Khôi phục mặc định
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
            {simulationError && !contextError && (
              <p className="text-xs text-rose-600 font-medium">{simulationError}</p>
            )}

            <div className="pt-2 flex items-center gap-2">
              <button
                type="button"
                data-testid="btn-run-simulation"
                onClick={handleRunSimulation}
                className="flex-1 rounded-lg bg-blue-600 py-2 text-xs font-semibold text-white hover:bg-blue-700 shadow-xs"
              >
                Chạy đến khi hoàn tất
              </button>
              {isRunning && (
                <button
                  type="button"
                  data-testid="btn-reset-simulation"
                  onClick={handleReset}
                  className="rounded-lg border border-slate-300 px-3 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50"
                >
                  Đặt lại
                </button>
              )}
            </div>
          </div>

          {/* Right Column: Execution Timeline & Trace */}
          <div className="col-span-3 flex flex-col space-y-3 overflow-hidden">
            <div className="flex items-center justify-between border-b border-slate-100 pb-2">
              <span className="text-xs font-bold text-slate-800">
                Luồng xử lý mô phỏng ({visibleSteps.length} / {steps.length} bước)
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
                    ◀ Bước trước
                  </button>
                  <button
                    type="button"
                    data-testid="btn-step-forward"
                    disabled={currentStepIndex >= steps.length - 1}
                    onClick={handleStepForward}
                    className="rounded border border-slate-200 px-2 py-1 text-xs font-semibold text-slate-700 disabled:opacity-30"
                  >
                    Bước sau ▶
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
                  Chưa có kết quả mô phỏng.
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
                          Tuyến: {s.evaluatedRoute}
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
                        🔀 Nhiều mục: tạo {s.fanOutCount} công việc song song
                      </p>
                    )}

                    {/* Join Synchronization */}
                    {s.joinBehavior && (
                      <p
                        data-testid="sim-join-behavior"
                        className="text-[11px] text-purple-700 font-medium"
                      >
                        ⚡ Hợp nhất: {s.joinBehavior}
                      </p>
                    )}

                    {/* SubWorkflow Mappings */}
                    {s.subWorkflowMappings && (
                      <div
                        data-testid="sim-subworkflow-mappings"
                        className="rounded bg-slate-50 p-1.5 text-[10px] font-mono text-slate-600"
                      >
                        Liên kết: {JSON.stringify(s.subWorkflowMappings)}
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
                  Bước hiện tại: <strong>{activeStep.nodeLabel}</strong> ({activeStep.nodeId})
                </span>
                <span className="font-mono text-[10px] text-slate-400">
                  Bước {currentStepIndex + 1}/{steps.length}
                </span>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
