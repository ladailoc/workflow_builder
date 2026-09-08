import type { BuilderEdge, BuilderNode, ValidationIssue } from "./types";
import { getNodeManifest } from "./manifest";

export function validateWorkflowGraph(
  nodes: BuilderNode[],
  edges: BuilderEdge[],
): ValidationIssue[] {
  const issues: ValidationIssue[] = [];

  // 1. Check START nodes
  const startNodes = nodes.filter((n) => n.data.nodeType === "START");
  if (startNodes.length === 0) {
    issues.push({
      id: "err-no-start",
      code: "ERR_NO_START",
      severity: "ERROR",
      message: "Workflow must contain exactly one Start node.",
    });
  } else if (startNodes.length > 1) {
    startNodes.slice(1).forEach((sn, idx) => {
      issues.push({
        id: `err-multi-start-${idx}`,
        code: "ERR_MULTI_START",
        nodeId: sn.id,
        severity: "ERROR",
        message: "Multiple Start nodes detected. Only one Start node is allowed.",
      });
    });
  }

  // 2. Check END nodes
  const endNodes = nodes.filter((n) => n.data.nodeType === "END");
  if (endNodes.length === 0) {
    issues.push({
      id: "err-no-end",
      code: "ERR_NO_END",
      severity: "ERROR",
      message: "Workflow must have at least one terminal End node.",
    });
  }

  // Build edge adjacency
  const incoming = new Map<string, BuilderEdge[]>();
  const outgoing = new Map<string, BuilderEdge[]>();

  edges.forEach((edge) => {
    if (!incoming.has(edge.target)) incoming.set(edge.target, []);
    incoming.get(edge.target)!.push(edge);

    if (!outgoing.has(edge.source)) outgoing.set(edge.source, []);
    outgoing.get(edge.source)!.push(edge);
  });

  // 3. Node-specific rules
  nodes.forEach((node) => {
    const manifest = getNodeManifest(node.data.nodeType);
    const inEdges = incoming.get(node.id) || [];
    const outEdges = outgoing.get(node.id) || [];

    // START cannot have incoming edges
    if (node.data.nodeType === "START" && inEdges.length > 0) {
      issues.push({
        id: `err-start-in-${node.id}`,
        code: "ERR_START_INCOMING",
        nodeId: node.id,
        severity: "ERROR",
        message: `Start node '${node.data.label}' cannot have incoming transitions.`,
      });
    }

    // END cannot have outgoing edges
    if (node.data.nodeType === "END" && outEdges.length > 0) {
      issues.push({
        id: `err-end-out-${node.id}`,
        code: "ERR_END_OUTGOING",
        nodeId: node.id,
        severity: "ERROR",
        message: `End node '${node.data.label}' cannot have outgoing transitions.`,
      });
    }

    // Non-START nodes should have incoming transitions
    if (node.data.nodeType !== "START" && inEdges.length === 0) {
      issues.push({
        id: `warn-unreachable-${node.id}`,
        code: "WARN_UNREACHABLE_NODE",
        nodeId: node.id,
        severity: "WARNING",
        message: `Node '${node.data.label}' is unreachable (no incoming transition).`,
      });
    }

    // Non-END nodes should have outgoing transitions
    if (node.data.nodeType !== "END" && outEdges.length === 0) {
      issues.push({
        id: `warn-dead-end-${node.id}`,
        code: "WARN_DEAD_END",
        nodeId: node.id,
        severity: "WARNING",
        message: `Node '${node.data.label}' is a dead end (no outgoing transition).`,
      });
    }

    // Port coverage check
    if (manifest && manifest.outputPorts.length > 0) {
      const connectedPorts = new Set(
        outEdges.map((e) => e.sourceHandle).filter(Boolean),
      );
      manifest.outputPorts.forEach((port) => {
        if (!connectedPorts.has(port) && outEdges.length > 0) {
          issues.push({
            id: `warn-port-${node.id}-${port}`,
            code: "ERR_PORT_UNCONNECTED",
            nodeId: node.id,
            field: `port.${port}`,
            severity: "WARNING",
            message: `Port '${port}' on node '${node.data.label}' is not connected.`,
          });
        }
      });
    }
  });

  return issues;
}
