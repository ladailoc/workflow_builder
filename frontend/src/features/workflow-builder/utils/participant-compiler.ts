import type {
  CompiledParticipantConfig,
  CompiledResolverPrimitive,
  FriendlyParticipantConfig,
  GenericResolverType,
} from "../participant-types";

/**
 * Compiles a single friendly resolver definition into a strict backend resolver primitive.
 */
export function compileResolverPrimitive(
  ui: FriendlyParticipantConfig,
): CompiledResolverPrimitive {
  switch (ui.kind) {
    case "FIXED_USER":
      return {
        type: "FIXED_USER",
        userId: ui.userId || "",
      };
    case "CREATOR":
      return {
        type: "CREATOR",
      };
    case "CREATORS_MANAGER":
      return {
        type: "MANAGER_OF",
        depth: 1,
      };
    case "MANAGER_N_LEVELS_UP":
      return {
        type: "MANAGER_OF",
        depth: Math.max(1, ui.depth || 2),
      };
    case "DEPARTMENT_HEAD":
      return {
        type: "HEAD_OF_UNIT",
      };
    case "ITEM_MANAGER":
      return {
        type: "ITEM_MANAGER",
        depth: Math.max(1, ui.depth || 1),
      };
    case "ITEM_USER":
      return {
        type: "ITEM_USER",
      };
    case "REQUEST_FIELD":
      return {
        type: "ITEM_USER",
        field: ui.fieldKey || "",
      };
    case "ROLE":
      return {
        type: "FIXED_USER",
        role: ui.role || "",
      };
    case "GROUP":
      return {
        type: "ITEM_USER",
        group: ui.group || "",
      };
    case "PREVIOUS_PARTICIPANT":
      return {
        type: "ITEM_USER",
        stepId: ui.stepId || "",
      };
    case "NODE_OUTPUT":
      return {
        type: "ITEM_USER",
        stepId: ui.stepId || "",
      };
    case "EXPRESSION":
      return {
        type: "FIXED_USER",
        expression: ui.expression || "",
      };
    default:
      return {
        type: "CREATOR",
      };
  }
}

/**
 * Compiles complete friendly UI participant configuration into a generic resolver config
 * with cardinality, task generation mode, completion policy, and fallback chain.
 */
export function compileParticipantConfig(
  ui: FriendlyParticipantConfig,
): CompiledParticipantConfig {
  const primitive = compileResolverPrimitive(ui);
  const cardinality = ui.cardinality ?? "SINGLE";
  const taskGenerationMode =
    cardinality === "MULTI"
      ? (ui.taskGenerationMode ?? "ONE_PER_PARTICIPANT")
      : "ONE_PER_PARTICIPANT";
  const completionPolicy =
    cardinality === "MULTI"
      ? (ui.completionPolicy ?? "ALL_MUST_APPROVE")
      : "FIRST_RESPONSE";

  const fallbackChain = Array.isArray(ui.fallbackChain)
    ? ui.fallbackChain.map(compileResolverPrimitive)
    : [];

  return {
    ...primitive,
    cardinality,
    taskGenerationMode,
    completionPolicy,
    ...(completionPolicy === "PERCENTAGE" && {
      completionPercentage: ui.completionPercentage ?? 50,
    }),
    ...(completionPolicy === "QUORUM" && {
      quorumCount: ui.quorumCount ?? 2,
    }),
    ...(fallbackChain.length > 0 && { fallbackChain }),
  };
}

/**
 * Decompiles a raw/compiled resolver config back to friendly UI state.
 */
export function decompileResolverPrimitive(
  compiled: Record<string, unknown>,
): FriendlyParticipantConfig {
  const type = String(compiled.type || "").toUpperCase() as GenericResolverType;

  switch (type) {
    case "MANAGER_OF": {
      const depth = Number(compiled.depth ?? 1);
      if (depth === 1) {
        return { kind: "CREATORS_MANAGER", depth: 1 };
      }
      return { kind: "MANAGER_N_LEVELS_UP", depth };
    }
    case "CREATOR":
      return { kind: "CREATOR" };
    case "HEAD_OF_UNIT":
      return { kind: "DEPARTMENT_HEAD" };
    case "ITEM_MANAGER":
      return { kind: "ITEM_MANAGER", depth: Number(compiled.depth ?? 1) };
    case "ITEM_USER": {
      if (compiled.field) {
        return { kind: "REQUEST_FIELD", fieldKey: String(compiled.field) };
      }
      if (compiled.group) {
        return { kind: "GROUP", group: String(compiled.group) };
      }
      if (compiled.stepId) {
        return { kind: "PREVIOUS_PARTICIPANT", stepId: String(compiled.stepId) };
      }
      return { kind: "ITEM_USER" };
    }
    case "FIXED_USER": {
      if (compiled.role) {
        return { kind: "ROLE", role: String(compiled.role) };
      }
      if (compiled.expression) {
        return { kind: "EXPRESSION", expression: String(compiled.expression) };
      }
      return { kind: "FIXED_USER", userId: String(compiled.userId ?? "") };
    }
    default:
      return { kind: "CREATOR" };
  }
}

/**
 * Decompiles complete participant config back to friendly UI object.
 */
export function decompileParticipantConfig(
  compiled: Record<string, unknown>,
): FriendlyParticipantConfig {
  const primary = decompileResolverPrimitive(compiled);
  const cardinality = (compiled.cardinality as "SINGLE" | "MULTI") || "SINGLE";
  const taskGenerationMode =
    (compiled.taskGenerationMode as "ONE_PER_PARTICIPANT" | "SINGLE_CLAIMABLE") ||
    "ONE_PER_PARTICIPANT";
  const completionPolicy =
    (compiled.completionPolicy as
      | "ALL_MUST_APPROVE"
      | "FIRST_RESPONSE"
      | "PERCENTAGE"
      | "QUORUM") || (cardinality === "MULTI" ? "ALL_MUST_APPROVE" : "FIRST_RESPONSE");

  const rawFallbacks = Array.isArray(compiled.fallbackChain)
    ? (compiled.fallbackChain as Record<string, unknown>[])
    : [];

  const fallbackChain = rawFallbacks.map(decompileResolverPrimitive);

  return {
    ...primary,
    cardinality,
    taskGenerationMode,
    completionPolicy,
    completionPercentage:
      typeof compiled.completionPercentage === "number"
        ? compiled.completionPercentage
        : undefined,
    quorumCount:
      typeof compiled.quorumCount === "number" ? compiled.quorumCount : undefined,
    fallbackChain,
  };
}

/**
 * Generates an informative runtime explanation of the participant resolution process.
 */
export function explainResolutionBehavior(ui: FriendlyParticipantConfig): string {
  const parts: string[] = [];

  // 1. Primary Resolution
  switch (ui.kind) {
    case "FIXED_USER":
      parts.push(`Resolves directly to user '${ui.userId || "unspecified"}'`);
      break;
    case "CREATOR":
      parts.push("Resolves to the creator / submitter of the request ticket");
      break;
    case "CREATORS_MANAGER":
      parts.push("Resolves to the direct manager (depth 1) of the ticket creator via organization hierarchy");
      break;
    case "MANAGER_N_LEVELS_UP":
      parts.push(`Resolves to the manager ${ui.depth || 2} levels above the creator in the organizational tree`);
      break;
    case "DEPARTMENT_HEAD":
      parts.push("Resolves to the unit manager / department head of the creator's organizational unit");
      break;
    case "ITEM_MANAGER":
      parts.push(`Resolves to the manager of the asset / item owner (depth ${ui.depth || 1})`);
      break;
    case "ITEM_USER":
      parts.push("Resolves to the designated user / beneficiary of the target item");
      break;
    case "REQUEST_FIELD":
      parts.push(`Extracts participant user identity dynamically from request payload field '${ui.fieldKey || "unknown"}'`);
      break;
    case "ROLE":
      parts.push(`Resolves active members holding the '${ui.role || "specified"}' organizational role`);
      break;
    case "GROUP":
      parts.push(`Resolves active members belonging to group '${ui.group || "specified"}'`);
      break;
    case "PREVIOUS_PARTICIPANT":
      parts.push(`Resolves the user who executed prior step '${ui.stepId || "previous"}'`);
      break;
    case "NODE_OUTPUT":
      parts.push(`Resolves participant ID from upstream node output '${ui.stepId || "output"}'`);
      break;
    case "EXPRESSION":
      parts.push(`Evaluates dynamic rule expression: "${ui.expression || "true"}"`);
      break;
  }

  // 2. Fallback Chain
  if (ui.fallbackChain && ui.fallbackChain.length > 0) {
    const fallbackDesc = ui.fallbackChain
      .map((fb, idx) => `[${idx + 1}] ${fb.kind.replace(/_/g, " ")}`)
      .join(" -> ");
    parts.push(`If primary resolution fails, evaluates fallback chain in order: ${fallbackDesc}`);
  } else {
    parts.push("If resolution fails, execution will default to ticket creator or fail according to failure policy.");
  }

  // 3. Task Generation & Cardinality
  if (ui.cardinality === "MULTI") {
    if (ui.taskGenerationMode === "SINGLE_CLAIMABLE") {
      parts.push("Creates 1 shared task claimable by any of the resolved participants (SINGLE_CLAIMABLE)");
    } else {
      parts.push("Spawns individual, separate task instances for each resolved participant (ONE_PER_PARTICIPANT)");
    }

    // 4. Completion Policy
    switch (ui.completionPolicy) {
      case "ALL_MUST_APPROVE":
        parts.push("Requires 100% unanimity (every assigned participant must approve)");
        break;
      case "FIRST_RESPONSE":
        parts.push("Completes immediately upon the first response submitted");
        break;
      case "PERCENTAGE":
        parts.push(`Requires approval from at least ${ui.completionPercentage ?? 50}% of participants`);
        break;
      case "QUORUM":
        parts.push(`Requires a QUORUM of at least ${ui.quorumCount ?? 2} affirmative approvals`);
        break;
    }
  } else {
    parts.push("Assigns a single task to the resolved participant. The node completes on their decision");
  }

  return parts.map((p) => p.replace(/\.+$/, "")).join(". ") + ".";
}
