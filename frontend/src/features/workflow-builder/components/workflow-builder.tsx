"use client";

import { useCallback, useMemo, useRef, useState } from "react";
import {
  ReactFlow,
  Background,
  Controls,
  applyNodeChanges,
  applyEdgeChanges,
  addEdge,
  type Connection,
  type EdgeChange,
  type NodeChange,
  type NodeTypes,
  type ReactFlowInstance,
  ConnectionLineType,
  MarkerType,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import { CustomWorkflowNode } from "./custom-workflow-node";
import { NodeCatalogPanel } from "./node-catalog-panel";
import { PropertiesPanel } from "./properties-panel";
import { ValidationPanel } from "./validation-panel";
import { BuilderToolbar } from "./builder-toolbar";
import {
  formatPortLabel,
  getNodeDisplayName,
  getNodeManifest,
  withRequiredNodeConfigDefaults,
} from "../manifest";
import { validateWorkflowGraph } from "../validator";
import type {
  BuilderEdge,
  BuilderNode,
  BuilderNodeType,
  ValidationIssue,
  WorkflowVersionDto,
} from "../types";
import type { FormSchema } from "../form-types";
import { FormBuilder } from "./form-builder";
import { EdgeEditorModal } from "./edge-editor-modal";
import { SimulationModal } from "./simulation-modal";
import { PublishModal } from "./publish-modal";
import { VersionHistoryDrawer } from "./version-history-drawer";
import { VersionDiffModal } from "./version-diff-modal";
import { ApiRequestError } from "@/shared/api/client";
import { createDefaultReworkConfig, toReworkPolicy } from "../editor-types";

interface WorkflowBuilderProps {
  workflowName?: string;
  readOnly?: boolean;
  publishAllowed?: boolean;
  initialVersion: WorkflowVersionDto;
  initialRequestForm?: FormSchema;
  historicalVersions?: WorkflowVersionDto[];
  onSave?: (
    nodes: BuilderNode[],
    edges: BuilderEdge[],
    requestForm: FormSchema,
  ) => Promise<void>;
  onSaveSuccess?: () => void;
  onValidate?: () => Promise<ValidationIssue[]>;
  onPublish?: (
    versionId: string,
    acknowledgedWarnings?: readonly string[],
  ) => Promise<string | void>;
  onPublishSuccess?: (publishedVersionId?: string) => void;
  onCloneAsNewDraft?: (sourceVersion: WorkflowVersionDto) => void;
  onOpenHistory?: () => void;
}

function graphSignature(nodes: BuilderNode[], edges: BuilderEdge[]): string {
  return JSON.stringify({ nodes, edges });
}

function mergeValidationIssues(
  localIssues: ValidationIssue[],
  serverIssues: ValidationIssue[],
  nodes: BuilderNode[],
  edges: BuilderEdge[],
): ValidationIssue[] {
  const localHas = (code: string, nodeId?: string) =>
    localIssues.some(
      (issue) =>
        issue.code === code &&
        (nodeId === undefined || issue.nodeId === nodeId),
    );

  const starts = nodes.filter((node) => node.data.nodeType === "START");
  const ends = nodes.filter((node) => node.data.nodeType === "END");

  const isAlreadyCoveredByCurrentGraph = (issue: ValidationIssue) => {
    switch (issue.code) {
      case "NO_START":
        return starts.length > 0 || localHas("ERR_NO_START");
      case "MULTIPLE_START":
        return starts.length <= 1 || localHas("ERR_MULTI_START");
      case "NO_END":
        return ends.length > 0 || localHas("ERR_NO_END");
      case "START_HAS_INCOMING": {
        const start = starts.find((node) => node.id === issue.nodeId);
        return (
          start === undefined ||
          !edges.some((edge) => edge.target === start.id) ||
          localHas("ERR_START_INCOMING", issue.nodeId)
        );
      }
      case "END_HAS_OUTGOING": {
        const end = ends.find((node) => node.id === issue.nodeId);
        return (
          end === undefined ||
          !edges.some((edge) => edge.source === end.id) ||
          localHas("ERR_END_OUTGOING", issue.nodeId)
        );
      }
      case "UNREACHABLE_NODE":
        return localHas("WARN_UNREACHABLE_NODE", issue.nodeId);
      case "NONTERMINAL_DEAD_END":
        return localHas("WARN_DEAD_END", issue.nodeId);
      default:
        return false;
    }
  };

  return [
    ...localIssues,
    ...serverIssues.filter((issue) => !isAlreadyCoveredByCurrentGraph(issue)),
  ];
}

const nodeTypes: NodeTypes = {
  workflowNode: CustomWorkflowNode,
};

let nodeCounter = 1;

function getSaveErrorMessage(error: unknown): string {
  if (error instanceof ApiRequestError) {
    const detail = (error.payload as { detail?: unknown } | undefined)?.detail;
    const message = typeof detail === "string" ? detail : error.message;
    return `${message} (${error.code})`;
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return "Không thể lưu bản nháp. Hãy kiểm tra sơ đồ và thử lại.";
}

export function WorkflowBuilder({
  workflowName,
  readOnly = false,
  publishAllowed = true,
  initialVersion,
  initialRequestForm,
  historicalVersions = [],
  onSave,
  onSaveSuccess,
  onValidate,
  onPublish,
  onPublishSuccess,
  onCloneAsNewDraft,
  onOpenHistory,
}: WorkflowBuilderProps) {
  const [currentVersion, setCurrentVersion] =
    useState<WorkflowVersionDto>(initialVersion);
  const [status, setStatus] = useState(initialVersion.status);
  const isDraft = status === "DRAFT" && !readOnly;

  // Nodes & Edges state
  const [nodes, setNodes] = useState<BuilderNode[]>(initialVersion.nodes);
  const [edges, setEdges] = useState<BuilderEdge[]>(initialVersion.edges);
  const savedGraphSignature = useRef(
    graphSignature(initialVersion.nodes, initialVersion.edges),
  );
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [hoveredEdgeId, setHoveredEdgeId] = useState<string | null>(null);
  const reactFlowInstance = useRef<ReactFlowInstance<
    BuilderNode,
    BuilderEdge
  > | null>(null);

  // Validation panel state
  const [validationIssues, setValidationIssues] = useState(() =>
    validateWorkflowGraph(initialVersion.nodes, initialVersion.edges),
  );
  const [validationPanelOpen, setValidationPanelOpen] = useState(false);
  const [saveSuccessMsg, setSaveSuccessMsg] = useState<string | null>(null);
  const [saveErrorMsg, setSaveErrorMsg] = useState<string | null>(null);

  // Workflow Request Form state
  const [requestFormOpen, setRequestFormOpen] = useState(false);
  const [requestFormSchema, setRequestFormSchema] = useState<FormSchema>(
    () => ({
      fields: initialRequestForm?.fields ?? [
        {
          key: "title",
          label: "Tiêu đề yêu cầu",
          type: "STRING",
          required: true,
        },
        {
          key: "departmentId",
          label: "Phòng ban",
          type: "STRING",
          required: true,
        },
      ],
    }),
  );

  // Lifecycle Modals State
  const [simulationOpen, setSimulationOpen] = useState(false);
  const [publishModalOpen, setPublishModalOpen] = useState(false);
  const [historyDrawerOpen, setHistoryDrawerOpen] = useState(false);
  const [diffModalOpen, setDiffModalOpen] = useState(false);
  const [diffBaseVersion, setDiffBaseVersion] = useState<
    WorkflowVersionDto | undefined
  >(historicalVersions[0]);

  const handleConfirmPublish = async (
    acknowledgedWarnings?: readonly string[],
  ) => {
    const publishedVersionId = onPublish
      ? acknowledgedWarnings === undefined
        ? await onPublish(currentVersion.id)
        : await onPublish(currentVersion.id, acknowledgedWarnings)
      : undefined;
    setStatus("PUBLISHED");
    setCurrentVersion((prev) => ({ ...prev, status: "PUBLISHED" }));
    setSaveSuccessMsg(
      `Đã phát hành thành công phiên bản #${currentVersion.versionNo}.`,
    );
    setTimeout(() => setSaveSuccessMsg(null), 4000);
    onPublishSuccess?.(
      typeof publishedVersionId === "string" ? publishedVersionId : undefined,
    );
  };

  const handleCloneAsNewDraft = (sourceVersion: WorkflowVersionDto) => {
    if (onCloneAsNewDraft) {
      onCloneAsNewDraft(sourceVersion);
      return;
    }
    // Audit-safe clone: duplicate historical version structure into active draft
    const clonedNodes = sourceVersion.nodes.map((n) => ({
      ...n,
      data: {
        ...n.data,
        readOnly: false,
      },
    }));
    const clonedEdges = sourceVersion.edges.map((e) => ({ ...e }));
    setNodes(clonedNodes);
    setEdges(clonedEdges);
    setStatus("DRAFT");
    setValidationIssues(validateWorkflowGraph(clonedNodes, clonedEdges));
    setHistoryDrawerOpen(false);
    setSaveSuccessMsg(
      `Đã sao chép phiên bản #${sourceVersion.versionNo} thành bản nháp mới.`,
    );
    setTimeout(() => setSaveSuccessMsg(null), 4000);
  };

  const handleOpenDiff = (ver: WorkflowVersionDto) => {
    setDiffBaseVersion(ver);
    setDiffModalOpen(true);
  };

  const selectedNode = useMemo(
    () => nodes.find((n) => n.id === selectedNodeId) ?? null,
    [nodes, selectedNodeId],
  );

  const hasErrors = useMemo(
    () => validationIssues.some((i) => i.severity === "ERROR"),
    [validationIssues],
  );

  // React Flow handlers
  const onNodesChange = useCallback(
    (changes: NodeChange<BuilderNode>[]) => {
      if (!isDraft) {
        // In read-only mode, only allow select changes
        const selectionOnly = changes.filter((c) => c.type === "select");
        setNodes((nds) => applyNodeChanges<BuilderNode>(selectionOnly, nds));
        return;
      }
      setNodes((nds) => {
        const next = applyNodeChanges<BuilderNode>(changes, nds);
        setValidationIssues(validateWorkflowGraph(next, edges));
        return next;
      });
    },
    [isDraft, edges],
  );

  const onEdgesChange = useCallback(
    (changes: EdgeChange<BuilderEdge>[]) => {
      if (!isDraft) return;
      setEdges((eds) => {
        const next = applyEdgeChanges(changes, eds);
        setValidationIssues(validateWorkflowGraph(nodes, next));
        return next;
      });
    },
    [isDraft, nodes],
  );

  const onConnect = useCallback(
    (connection: Connection) => {
      if (!isDraft) return;

      const sourceNode = nodes.find((n) => n.id === connection.source);
      const targetNode = nodes.find((n) => n.id === connection.target);

      // Validate connection rules: END cannot have outgoing; START cannot have incoming
      if (sourceNode?.data.nodeType === "END") {
        setValidationIssues((prev) => [
          ...prev,
          {
            id: `err-connect-${Date.now()}`,
            nodeId: sourceNode.id,
            severity: "ERROR",
            message: "Không thể tạo chuyển tiếp đi ra từ bước kết thúc.",
          },
        ]);
        setValidationPanelOpen(true);
        return;
      }

      if (targetNode?.data.nodeType === "START") {
        setValidationIssues((prev) => [
          ...prev,
          {
            id: `err-connect-${Date.now()}`,
            nodeId: targetNode.id,
            severity: "ERROR",
            message: "Không thể tạo chuyển tiếp đi vào bước bắt đầu.",
          },
        ]);
        setValidationPanelOpen(true);
        return;
      }

      setEdges((eds) => {
        const revisionBranch =
          connection.sourceHandle === "REVISION_REQUESTED"
            ? createDefaultReworkConfig(connection.target)
            : undefined;
        const next = addEdge(
          {
            ...connection,
            id: `edge_${connection.source}_${connection.target}_${connection.sourceHandle || "default"}`,
            data: revisionBranch
              ? {
                  transitionType: "REWORK",
                  reworkConfig: revisionBranch,
                  config: { reworkPolicy: toReworkPolicy(revisionBranch) },
                }
              : undefined,
          },
          eds,
        );
        setValidationIssues(validateWorkflowGraph(nodes, next));
        return next;
      });
    },
    [isDraft, nodes],
  );

  const [selectedEdge, setSelectedEdge] = useState<BuilderEdge | null>(null);

  const displayEdges = useMemo(() => {
    const nodesById = new Map(nodes.map((node) => [node.id, node]));

    return edges.map((edge) => {
      const isHovered = edge.id === hoveredEdgeId;
      const sourceNode = nodesById.get(edge.source);
      const targetNode = nodesById.get(edge.target);
      const sourceLabel = sourceNode
        ? getNodeDisplayName(sourceNode.data.nodeType, sourceNode.data.label)
        : edge.source;
      const targetLabel = targetNode
        ? getNodeDisplayName(targetNode.data.nodeType, targetNode.data.label)
        : edge.target;
      const sourcePort = edge.sourceHandle
        ? edge.sourceHandle === "DEFAULT"
          ? ""
          : ` · ${formatPortLabel(edge.sourceHandle)}`
        : "";
      const baseStroke =
        typeof edge.style?.stroke === "string" ? edge.style.stroke : "#2563eb";
      const baseStrokeWidth =
        typeof edge.style?.strokeWidth === "number"
          ? edge.style.strokeWidth
          : 2;

      return {
        ...edge,
        animated: edge.animated,
        zIndex: isHovered ? Math.max(edge.zIndex ?? 0, 5) : edge.zIndex,
        label: isHovered
          ? `${sourceLabel} → ${targetLabel}${sourcePort}`
          : edge.label,
        labelShowBg: isHovered ? true : edge.labelShowBg,
        labelStyle: isHovered
          ? { fill: "#1e3a8a", fontSize: 10, fontWeight: 600 }
          : edge.labelStyle,
        labelBgStyle: isHovered
          ? {
              fill: "#eff6ff",
              fillOpacity: 0.98,
              stroke: "#93c5fd",
              strokeWidth: 1,
            }
          : edge.labelBgStyle,
        labelBgPadding: isHovered
          ? ([6, 3] as [number, number])
          : edge.labelBgPadding,
        labelBgBorderRadius: isHovered ? 6 : edge.labelBgBorderRadius,
        style: {
          ...edge.style,
          stroke: isHovered ? "#2563eb" : baseStroke,
          strokeWidth: isHovered
            ? Math.max(baseStrokeWidth, 2.75)
            : baseStrokeWidth,
          filter: isHovered
            ? "drop-shadow(0 0 2px rgba(37, 99, 235, 0.35))"
            : edge.style?.filter,
        },
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: isHovered ? "#2563eb" : baseStroke,
          width: isHovered ? 19 : 18,
          height: isHovered ? 19 : 18,
        },
      };
    });
  }, [edges, hoveredEdgeId, nodes]);

  const handleNodeClick = useCallback(
    (_: React.MouseEvent, node: BuilderNode) => {
      setSelectedNodeId(node.id);
    },
    [],
  );

  const handleEdgeClick = useCallback(
    (_: React.MouseEvent, edge: BuilderEdge) => {
      setSelectedEdge(edge);
    },
    [],
  );

  const handleSaveEdge = useCallback(
    (updatedEdge: BuilderEdge) => {
      setEdges((prev) => {
        const next = prev.map((e) =>
          e.id === updatedEdge.id ? updatedEdge : e,
        );
        setValidationIssues(validateWorkflowGraph(nodes, next));
        return next;
      });
    },
    [nodes],
  );

  const handleDeleteEdge = useCallback(
    (edgeId: string) => {
      setEdges((prev) => {
        const next = prev.filter((e) => e.id !== edgeId);
        setValidationIssues(validateWorkflowGraph(nodes, next));
        return next;
      });
      setSelectedEdge(null);
    },
    [nodes],
  );

  const handlePaneClick = useCallback(() => {
    setSelectedNodeId(null);
    setSelectedEdge(null);
    setHoveredEdgeId(null);
  }, []);

  // Add node from catalog
  const handleAddNode = useCallback(
    (type: BuilderNodeType, position?: { x: number; y: number }) => {
      if (!isDraft) return;

      const manifest = getNodeManifest(type);
      const key = `${type.toLowerCase()}_${nodeCounter++}`;
      const newNode: BuilderNode = {
        id: `node_${key}`,
        type: "workflowNode",
        position: position ?? {
          x: 250 + (nodes.length % 4) * 60,
          y: 150 + (nodes.length % 4) * 60,
        },
        data: {
          key,
          label: manifest?.name ?? type,
          nodeType: type,
          outputPorts: manifest?.outputPorts ? [...manifest.outputPorts] : [],
          config: withRequiredNodeConfigDefaults(type, {}),
          readOnly: !isDraft,
        },
      };

      const nextNodes = [...nodes, newNode];
      setNodes(nextNodes);
      setSelectedNodeId(newNode.id);
      setValidationIssues(validateWorkflowGraph(nextNodes, edges));
    },
    [edges, isDraft, nodes],
  );

  const handleDragOver = useCallback(
    (event: React.DragEvent<HTMLElement>) => {
      if (!isDraft) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = "move";
    },
    [isDraft],
  );

  const handleDrop = useCallback(
    (event: React.DragEvent<HTMLElement>) => {
      event.preventDefault();
      if (!isDraft) return;

      const rawType =
        event.dataTransfer.getData("application/x-workflow-node") ||
        event.dataTransfer.getData("application/reactflow");
      if (!getNodeManifest(rawType)) return;

      const position = reactFlowInstance.current?.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      }) ?? { x: event.clientX, y: event.clientY };

      handleAddNode(rawType as BuilderNodeType, position);
    },
    [handleAddNode, isDraft],
  );

  // Update properties of selected node
  const handleUpdateNode = (
    nodeId: string,
    updates: Partial<BuilderNode["data"]>,
  ) => {
    if (!isDraft) return;
    setNodes((prev) =>
      prev.map((n) => {
        if (n.id === nodeId) {
          const updatedData = { ...n.data, ...updates };
          return { ...n, data: updatedData };
        }
        return n;
      }),
    );
  };

  // Delete node
  const handleDeleteNode = (nodeId: string) => {
    if (!isDraft) return;
    const nextNodes = nodes.filter((n) => n.id !== nodeId);
    const nextEdges = edges.filter(
      (e) => e.source !== nodeId && e.target !== nodeId,
    );
    setNodes(nextNodes);
    setEdges(nextEdges);
    setSelectedNodeId(null);
    setValidationIssues(validateWorkflowGraph(nextNodes, nextEdges));
  };

  // Toolbar actions
  const handleValidate = async () => {
    const localIssues = validateWorkflowGraph(nodes, edges);
    const hasUnsavedGraphChanges =
      graphSignature(nodes, edges) !== savedGraphSignature.current;

    // The API validates the persisted version. While editing, that version can
    // be behind the canvas, so showing its structural result would report
    // false NO_START/NO_END errors for nodes that are already visible here.
    if (onValidate && !hasUnsavedGraphChanges) {
      const serverIssues = await onValidate();
      setValidationIssues(
        mergeValidationIssues(localIssues, serverIssues, nodes, edges),
      );
    } else {
      setValidationIssues(localIssues);
    }
    setValidationPanelOpen(true);
  };

  const handleSaveDraft = async () => {
    if (!isDraft) return;
    setSaveErrorMsg(null);
    try {
      if (onSave) {
        await onSave(nodes, edges, requestFormSchema);
      }
      savedGraphSignature.current = graphSignature(nodes, edges);
      setSaveSuccessMsg("Đã lưu bản nháp thành công.");
      setTimeout(() => setSaveSuccessMsg(null), 3000);
      onSaveSuccess?.();
    } catch (error) {
      setSaveSuccessMsg(null);
      setSaveErrorMsg(getSaveErrorMessage(error));
    }
  };

  const handleSelectValidationIssue = (nodeId?: string) => {
    if (nodeId) {
      setSelectedNodeId(nodeId);
    }
  };

  return (
    <div
      data-testid="workflow-builder-shell"
      className="flex h-[calc(100vh-4rem)] flex-col overflow-hidden bg-slate-100"
    >
      {/* Top Toolbar */}
      <BuilderToolbar
        workflowName={workflowName}
        versionNo={currentVersion.versionNo}
        status={status}
        editingAllowed={isDraft}
        publishAllowed={publishAllowed}
        hasErrors={hasErrors}
        issueCount={validationIssues.length}
        onSaveDraft={handleSaveDraft}
        onValidate={handleValidate}
        onSimulate={() => setSimulationOpen(true)}
        onDiffHistory={() => {
          if (onOpenHistory) {
            onOpenHistory();
            return;
          }
          setHistoryDrawerOpen(true);
        }}
        onPublish={() => setPublishModalOpen(true)}
        onRequestForm={() => setRequestFormOpen(true)}
        onCloneAsNewDraft={() => handleCloneAsNewDraft(currentVersion)}
      />

      {/* Save feedback toast */}
      {saveSuccessMsg && (
        <div
          data-testid="save-draft-toast"
          className="animate-fade-in absolute top-16 right-8 z-50 rounded-lg bg-emerald-600 px-4 py-2 text-xs font-semibold text-white shadow-lg"
        >
          {saveSuccessMsg}
        </div>
      )}
      {saveErrorMsg && (
        <div
          role="alert"
          data-testid="save-draft-error"
          className="animate-fade-in absolute top-16 right-8 z-50 max-w-lg rounded-lg bg-rose-600 px-4 py-2 text-xs font-semibold text-white shadow-lg"
        >
          {saveErrorMsg}
        </div>
      )}

      {/* Builder Panels: Left Catalog, Center Canvas, Right Properties */}
      <div className="relative flex flex-1 overflow-hidden">
        {/* Left: Node Catalog */}
        <NodeCatalogPanel onAddNode={handleAddNode} readOnly={!isDraft} />

        {/* Center: React Flow Canvas */}
        <main
          data-testid="react-flow-canvas"
          className="relative flex-1 bg-slate-50/50"
        >
          <ReactFlow<BuilderNode, BuilderEdge>
            nodes={nodes}
            edges={displayEdges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={handleNodeClick}
            onEdgeClick={handleEdgeClick}
            onEdgeMouseEnter={(_, edge) => setHoveredEdgeId(edge.id)}
            onEdgeMouseLeave={() => setHoveredEdgeId(null)}
            onPaneClick={handlePaneClick}
            onInit={(instance) => {
              reactFlowInstance.current = instance;
            }}
            onDrop={handleDrop}
            onDragOver={handleDragOver}
            nodesDraggable={isDraft}
            nodesConnectable={isDraft}
            elementsSelectable={true}
            connectionLineType={ConnectionLineType.SmoothStep}
            connectionLineStyle={{ stroke: "#2563eb", strokeWidth: 2 }}
            defaultEdgeOptions={{
              type: "smoothstep",
              style: { stroke: "#2563eb", strokeWidth: 2 },
              markerEnd: {
                type: MarkerType.ArrowClosed,
                color: "#2563eb",
                width: 18,
                height: 18,
              },
              interactionWidth: 24,
            }}
            fitView
            className="h-full w-full"
          >
            <Background gap={16} size={1} color="#cbd5e1" />
            <Controls showInteractive={isDraft} />
          </ReactFlow>

          {nodes.length === 0 && (
            <div
              data-testid="canvas-drop-hint"
              className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center p-8 text-center"
            >
              <div className="rounded-xl border border-dashed border-blue-200 bg-white/80 px-6 py-5 shadow-sm backdrop-blur-sm">
                <p className="text-sm font-semibold text-slate-700">
                  Chưa có bước nào
                </p>
              </div>
            </div>
          )}

          {/* Collapsible Validation Panel */}
          <ValidationPanel
            issues={validationIssues}
            isOpen={validationPanelOpen}
            onClose={() => setValidationPanelOpen(false)}
            onSelectIssue={handleSelectValidationIssue}
          />
        </main>

        {/* Right: Properties Panel */}
        <PropertiesPanel
          selectedNode={selectedNode}
          requestForm={requestFormSchema}
          onUpdateNode={handleUpdateNode}
          onDeleteNode={handleDeleteNode}
          onClose={() => setSelectedNodeId(null)}
          readOnly={!isDraft}
        />
      </div>

      {/* Edge Transition Editor Modal */}
      {selectedEdge && (
        <EdgeEditorModal
          isOpen={true}
          edge={selectedEdge}
          nodes={nodes}
          onSave={handleSaveEdge}
          onDelete={handleDeleteEdge}
          onClose={() => setSelectedEdge(null)}
          readOnly={!isDraft}
        />
      )}

      {/* Workflow Request Input Form Modal */}
      {requestFormOpen && (
        <div
          role="dialog"
          aria-modal="true"
          data-testid="workflow-request-form-modal"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-xs"
        >
          <div className="animate-scale-in w-full max-w-2xl space-y-4 rounded-xl bg-white p-6 shadow-2xl">
            <div className="flex items-center justify-between border-b border-slate-200 pb-3">
              <div>
                <h3 className="text-sm font-bold text-slate-900">
                  Biểu mẫu đầu vào của yêu cầu
                </h3>
              </div>
              <button
                type="button"
                data-testid="close-request-form-modal-btn"
                onClick={() => setRequestFormOpen(false)}
                className="rounded-lg border border-slate-200 px-2.5 py-1 text-xs font-semibold text-slate-600 hover:bg-slate-50"
              >
                Đóng
              </button>
            </div>

            <div className="max-h-[65vh] overflow-y-auto pr-1">
              <FormBuilder
                schema={requestFormSchema}
                onChange={setRequestFormSchema}
                nodes={nodes}
                edges={edges}
                readOnly={!isDraft}
                title="Các trường đầu vào của yêu cầu"
              />
            </div>
          </div>
        </div>
      )}

      {/* Simulation Sandbox Modal */}
      {simulationOpen && (
        <SimulationModal
          isOpen={true}
          nodes={nodes}
          edges={edges}
          workflowId={currentVersion.definitionId}
          versionId={currentVersion.id}
          onClose={() => setSimulationOpen(false)}
        />
      )}

      {/* Publish Gate Modal */}
      {publishModalOpen && (
        <PublishModal
          isOpen={true}
          version={currentVersion}
          nodes={nodes}
          edges={edges}
          onConfirmPublish={handleConfirmPublish}
          onValidate={onValidate}
          onClose={() => setPublishModalOpen(false)}
          onSelectIssue={handleSelectValidationIssue}
        />
      )}

      {/* Version History & Audit-Safe Rollback Drawer */}
      {historyDrawerOpen && (
        <VersionHistoryDrawer
          isOpen={true}
          currentVersion={currentVersion}
          versions={[
            currentVersion,
            ...historicalVersions.filter((v) => v.id !== currentVersion.id),
          ]}
          onCloneAsNewDraft={handleCloneAsNewDraft}
          onOpenDiff={handleOpenDiff}
          onClose={() => setHistoryDrawerOpen(false)}
        />
      )}

      {/* Semantic Version Diff Modal */}
      {diffModalOpen && (
        <VersionDiffModal
          isOpen={true}
          currentVersion={currentVersion}
          historicalVersions={
            diffBaseVersion
              ? [
                  diffBaseVersion,
                  ...historicalVersions.filter(
                    (v) => v.id !== diffBaseVersion.id,
                  ),
                ]
              : historicalVersions
          }
          onClose={() => setDiffModalOpen(false)}
        />
      )}
    </div>
  );
}
