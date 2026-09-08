"use client";

import { useState } from "react";
import { AuthRouteGuard } from "@/features/auth";
import {
  WorkflowBuilder,
  type BuilderEdge,
  type BuilderNode,
  type WorkflowVersionDto,
} from "@/features/workflow-builder";

const SAMPLE_DRAFT_VERSION: WorkflowVersionDto = {
  id: "wf-ver-draft-1",
  definitionId: "wf-def-1",
  versionNo: 1,
  status: "DRAFT",
  revision: 1,
  nodes: [
    {
      id: "node_start_1",
      type: "workflowNode",
      position: { x: 50, y: 160 },
      data: {
        key: "start_1",
        label: "Start",
        nodeType: "START",
        outputPorts: ["DEFAULT"],
        config: {},
      },
    },
    {
      id: "node_approval_1",
      type: "workflowNode",
      position: { x: 340, y: 140 },
      data: {
        key: "manager_approval",
        label: "Manager Approval",
        nodeType: "APPROVAL",
        outputPorts: ["APPROVED", "REJECTED"],
        config: {
          participantResolver: "DIRECT_MANAGER",
          slaMinutes: 1440,
        },
      },
    },
    {
      id: "node_end_approved",
      type: "workflowNode",
      position: { x: 650, y: 80 },
      data: {
        key: "end_approved",
        label: "Approved End",
        nodeType: "END",
        outputPorts: [],
        config: {},
      },
    },
    {
      id: "node_end_rejected",
      type: "workflowNode",
      position: { x: 650, y: 240 },
      data: {
        key: "end_rejected",
        label: "Rejected End",
        nodeType: "END",
        outputPorts: [],
        config: {},
      },
    },
  ],
  edges: [
    {
      id: "edge_start_to_approval",
      source: "node_start_1",
      target: "node_approval_1",
      sourceHandle: "DEFAULT",
    },
    {
      id: "edge_approval_to_end_approved",
      source: "node_approval_1",
      target: "node_end_approved",
      sourceHandle: "APPROVED",
    },
    {
      id: "edge_approval_to_end_rejected",
      source: "node_approval_1",
      target: "node_end_rejected",
      sourceHandle: "REJECTED",
    },
  ],
};

export default function WorkflowsPage() {
  const [currentVersion, setCurrentVersion] =
    useState<WorkflowVersionDto>(SAMPLE_DRAFT_VERSION);

  const handleSave = async (nodes: BuilderNode[], edges: BuilderEdge[]) => {
    setCurrentVersion((prev) => ({
      ...prev,
      nodes,
      edges,
      revision: prev.revision + 1,
    }));
  };

  const handlePublish = async (versionId: string) => {
    setCurrentVersion((prev) => ({
      ...prev,
      id: versionId,
      status: "PUBLISHED",
    }));
  };

  return (
    <AuthRouteGuard roles={["WORKFLOW_OWNER", "WORKFLOW_EDITOR", "ADMIN"]}>
      <WorkflowBuilder
        initialVersion={currentVersion}
        onSave={handleSave}
        onPublish={handlePublish}
      />
    </AuthRouteGuard>
  );
}
