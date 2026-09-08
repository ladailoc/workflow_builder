import type { BuilderEdge, BuilderNode } from "../types";
import type { FieldDependencyReference } from "../form-types";

/**
 * Inspects workflow graph dependencies before breaking delete/rename of a form field.
 * Flags references in transition conditions, participant expressions, title templates,
 * and child workflow mappings.
 */
export function findFieldDependencies(
  fieldKey: string,
  nodes: BuilderNode[] = [],
  edges: BuilderEdge[] = [],
): FieldDependencyReference[] {
  if (!fieldKey || !fieldKey.trim()) return [];
  const normalizedKey = fieldKey.trim();
  const refs: FieldDependencyReference[] = [];

  // Helper to test if a string contains fieldKey as variable or template
  const containsFieldRef = (text: string): boolean => {
    if (!text) return false;
    // matches fieldKey as exact word, ${fieldKey}, payload.fieldKey, etc.
    const pattern = new RegExp(`\\b${normalizedKey}\\b|\\$\\{${normalizedKey}\\}|\\{${normalizedKey}\\}`, "i");
    return pattern.test(text);
  };

  // 1. Inspect Edges (transition conditions)
  edges.forEach((edge) => {
    const label = edge.label ? String(edge.label) : "";
    const edgeData = edge.data as Record<string, unknown> | undefined;
    const cond = edgeData?.condition ?? edgeData?.expression;
    const condStr = cond ? (typeof cond === "object" ? JSON.stringify(cond) : String(cond)) : "";

    if (containsFieldRef(label) || containsFieldRef(condStr)) {
      refs.push({
        type: "TRANSITION_CONDITION",
        targetId: edge.id,
        targetName: edge.label ? `Edge '${edge.label}'` : `Transition (${edge.source} -> ${edge.target})`,
        detail: `Referenced in transition condition: "${label || condStr}"`,
      });
    }
  });

  // 2. Inspect Nodes
  nodes.forEach((node) => {
    const config = node.data.config || {};
    const nodeLabel = node.data.label || node.id;

    // A. Condition Nodes
    if (node.data.nodeType === "CONDITION") {
      const expr = config.expression;
      const exprStr = expr ? (typeof expr === "object" ? JSON.stringify(expr) : String(expr)) : "";
      if (containsFieldRef(exprStr)) {
        refs.push({
          type: "TRANSITION_CONDITION",
          targetId: node.id,
          targetName: `Condition Node '${nodeLabel}'`,
          detail: `Referenced in condition rule expression: "${exprStr.slice(0, 80)}"`,
        });
      }
    }

    // B. Participant Expressions
    if (config.participant) {
      const partStr =
        typeof config.participant === "object"
          ? JSON.stringify(config.participant)
          : String(config.participant);
      if (containsFieldRef(partStr)) {
        refs.push({
          type: "PARTICIPANT_EXPRESSION",
          targetId: node.id,
          targetName: `Node '${nodeLabel}'`,
          detail: `Referenced in participant resolver configuration`,
        });
      }
    }

    // C. Title / Description Templates
    const title = String(config.titleSnapshot ?? "");
    const desc = String(config.descriptionSnapshot ?? "");
    if (containsFieldRef(title) || containsFieldRef(desc)) {
      refs.push({
        type: "TITLE_TEMPLATE",
        targetId: node.id,
        targetName: `Node '${nodeLabel}'`,
        detail: `Referenced in title/description snapshot template: "${title || desc}"`,
      });
    }

    // D. Child Workflow Mappings (SUB_WORKFLOW)
    if (node.data.nodeType === "SUB_WORKFLOW") {
      const mappings =
        config.inputMappings || config.variableMappings || config.mappings;
      const mapStr = mappings ? JSON.stringify(mappings) : "";
      if (containsFieldRef(mapStr)) {
        refs.push({
          type: "CHILD_WORKFLOW_MAPPING",
          targetId: node.id,
          targetName: `SubWorkflow Node '${nodeLabel}'`,
          detail: `Referenced in sub-workflow parameter mapping`,
        });
      }
    }
  });

  return refs;
}
