"use client";

import { useCallback, useMemo, useState } from "react";
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
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import { CustomWorkflowNode } from "./custom-workflow-node";
import { NodeCatalogPanel } from "./node-catalog-panel";
import { PropertiesPanel } from "./properties-panel";
import { ValidationPanel } from "./validation-panel";
import { BuilderToolbar } from "./builder-toolbar";
import { getNodeManifest } from "../manifest";
import { validateWorkflowGraph } from "../validator";
import type {
  BuilderEdge,
  BuilderNode,
  BuilderNodeType,
  WorkflowVersionDto,
} from "../types";
import type { FormSchema } from "../form-types";
import { FormBuilder } from "./form-builder";
import { EdgeEditorModal } from "./edge-editor-modal";
import { SimulationModal } from "./simulation-modal";
import { PublishModal } from "./publish-modal";
import { VersionHistoryDrawer } from "./version-history-drawer";
import { VersionDiffModal } from "./version-diff-modal";

interface WorkflowBuilderProps {
  initialVersion: WorkflowVersionDto;
  historicalVersions?: WorkflowVersionDto[];
  onSave?: (nodes: BuilderNode[], edges: BuilderEdge[]) => Promise<void>;
  onPublish?: (versionId: string) => Promise<void>;
  onCloneAsNewDraft?: (sourceVersion: WorkflowVersionDto) => void;
}

const nodeTypes: NodeTypes = {
  workflowNode: CustomWorkflowNode,
};

let nodeCounter = 1;

export function WorkflowBuilder({
  initialVersion,
  historicalVersions = [],
  onSave,
  onPublish,
  onCloneAsNewDraft,
}: WorkflowBuilderProps) {
  const [currentVersion, setCurrentVersion] = useState<WorkflowVersionDto>(initialVersion);
  const [status, setStatus] = useState(initialVersion.status);
  const isDraft = status === "DRAFT";

  // Nodes & Edges state
  const [nodes, setNodes] = useState<BuilderNode[]>(initialVersion.nodes);
  const [edges, setEdges] = useState<BuilderEdge[]>(initialVersion.edges);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);

  // Validation panel state
  const [validationIssues, setValidationIssues] = useState(() =>
    validateWorkflowGraph(initialVersion.nodes, initialVersion.edges),
  );
  const [validationPanelOpen, setValidationPanelOpen] = useState(false);
  const [saveSuccessMsg, setSaveSuccessMsg] = useState<string | null>(null);

  // Workflow Request Form state
  const [requestFormOpen, setRequestFormOpen] = useState(false);
  const [requestFormSchema, setRequestFormSchema] = useState<FormSchema>(() => ({
    fields: [
      { key: "title", label: "Request Title", type: "STRING", required: true },
      { key: "departmentId", label: "Department", type: "STRING", required: true },
    ],
  }));

  // Lifecycle Modals State
  const [simulationOpen, setSimulationOpen] = useState(false);
  const [publishModalOpen, setPublishModalOpen] = useState(false);
  const [historyDrawerOpen, setHistoryDrawerOpen] = useState(false);
  const [diffModalOpen, setDiffModalOpen] = useState(false);
  const [diffBaseVersion, setDiffBaseVersion] = useState<WorkflowVersionDto | undefined>(
    historicalVersions[0],
  );

  const handleConfirmPublish = async () => {
    if (onPublish) {
      await onPublish(currentVersion.id);
    }
    setStatus("PUBLISHED");
    setCurrentVersion((prev) => ({ ...prev, status: "PUBLISHED" }));
    setSaveSuccessMsg(`Version #${currentVersion.versionNo} published successfully.`);
    setTimeout(() => setSaveSuccessMsg(null), 4000);
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
      `Cloned Version #${sourceVersion.versionNo} into new draft revision.`,
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
            message: "Cannot create an outgoing transition from an End node.",
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
            message: "Cannot create an incoming transition to a Start node.",
          },
        ]);
        setValidationPanelOpen(true);
        return;
      }

      setEdges((eds) => {
        const next = addEdge(
          {
            ...connection,
            id: `edge_${connection.source}_${connection.target}_${connection.sourceHandle || "default"}`,
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
        const next = prev.map((e) => (e.id === updatedEdge.id ? updatedEdge : e));
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
  }, []);

  // Add node from catalog
  const handleAddNode = (type: BuilderNodeType) => {
    if (!isDraft) return;

    const manifest = getNodeManifest(type);
    const key = `${type.toLowerCase()}_${nodeCounter++}`;
    const newNode: BuilderNode = {
      id: `node_${key}`,
      type: "workflowNode",
      position: {
        x: 250 + (nodes.length % 4) * 60,
        y: 150 + (nodes.length % 4) * 60,
      },
      data: {
        key,
        label: `${manifest?.name ?? type} Task`,
        nodeType: type,
        outputPorts: manifest?.outputPorts ? [...manifest.outputPorts] : [],
        config: {},
        readOnly: !isDraft,
      },
    };

    const nextNodes = [...nodes, newNode];
    setNodes(nextNodes);
    setSelectedNodeId(newNode.id);
    setValidationIssues(validateWorkflowGraph(nextNodes, edges));
  };

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
  const handleValidate = () => {
    const issues = validateWorkflowGraph(nodes, edges);
    setValidationIssues(issues);
    setValidationPanelOpen(true);
  };

  const handleSaveDraft = async () => {
    if (!isDraft) return;
    if (onSave) {
      await onSave(nodes, edges);
    }
    setSaveSuccessMsg("Draft saved successfully.");
    setTimeout(() => setSaveSuccessMsg(null), 3000);
  };

  const handleSelectValidationIssue = (nodeId?: string) => {
    if (nodeId) {
      setSelectedNodeId(nodeId);
    }
  };

  return (
    <div
      data-testid="workflow-builder-shell"
      className="flex h-[calc(100vh-4rem)] flex-col bg-slate-100 overflow-hidden"
    >
      {/* Top Toolbar */}
      <BuilderToolbar
        versionNo={currentVersion.versionNo}
        status={status}
        hasErrors={hasErrors}
        issueCount={validationIssues.length}
        onSaveDraft={handleSaveDraft}
        onValidate={handleValidate}
        onSimulate={() => setSimulationOpen(true)}
        onDiffHistory={() => setHistoryDrawerOpen(true)}
        onPublish={() => setPublishModalOpen(true)}
        onRequestForm={() => setRequestFormOpen(true)}
      />

      {/* Save feedback toast */}
      {saveSuccessMsg && (
        <div
          data-testid="save-draft-toast"
          className="absolute top-16 right-8 z-50 rounded-lg bg-emerald-600 px-4 py-2 text-xs font-semibold text-white shadow-lg animate-fade-in"
        >
          {saveSuccessMsg}
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
            edges={edges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={handleNodeClick}
            onEdgeClick={handleEdgeClick}
            onPaneClick={handlePaneClick}
            nodesDraggable={isDraft}
            nodesConnectable={isDraft}
            elementsSelectable={true}
            fitView
            className="h-full w-full"
          >
            <Background gap={16} size={1} color="#cbd5e1" />
            <Controls showInteractive={isDraft} />
          </ReactFlow>

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
          onUpdateNode={handleUpdateNode}
          onDeleteNode={handleDeleteNode}
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
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-xs p-4"
        >
          <div className="w-full max-w-2xl rounded-xl bg-white p-6 shadow-2xl space-y-4 animate-scale-in">
            <div className="flex items-center justify-between border-b border-slate-200 pb-3">
              <div>
                <h3 className="text-sm font-bold text-slate-900">
                  Workflow Request Input Form
                </h3>
                <p className="text-xs text-slate-500">
                  Configure form schema presented to users when submitting a ticket for this workflow.
                </p>
              </div>
              <button
                type="button"
                data-testid="close-request-form-modal-btn"
                onClick={() => setRequestFormOpen(false)}
                className="rounded-lg border border-slate-200 px-2.5 py-1 text-xs font-semibold text-slate-600 hover:bg-slate-50"
              >
                Close
              </button>
            </div>

            <div className="max-h-[65vh] overflow-y-auto pr-1">
              <FormBuilder
                schema={requestFormSchema}
                onChange={setRequestFormSchema}
                nodes={nodes}
                edges={edges}
                readOnly={!isDraft}
                title="Request Input Fields"
                description="Fields entered by users during request submission."
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
                  ...historicalVersions.filter((v) => v.id !== diffBaseVersion.id),
                ]
              : historicalVersions
          }
          onClose={() => setDiffModalOpen(false)}
        />
      )}
    </div>
  );
}
