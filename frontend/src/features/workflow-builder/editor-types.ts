export type EdgeTransitionType = "NORMAL" | "REWORK" | "RETURN";

export type ConditionOperator =
  | "EQ"
  | "NEQ"
  | "GT"
  | "GTE"
  | "LT"
  | "LTE"
  | "IN"
  | "CONTAINS"
  | "IS_NULL"
  | "NOT_NULL";

export type LogicalOperator = "AND" | "OR" | "NOT";

export interface AtomicConditionClause {
  id: string;
  field: string; // e.g. "payload.totalAmount", "event.creatorId", "system.currentDate"
  operator: ConditionOperator;
  value: string;
}

export interface UiConditionGroup {
  id: string;
  logical: LogicalOperator;
  clauses: (AtomicConditionClause | UiConditionGroup)[];
}

export type MultiInstanceConcurrency = "PARALLEL" | "SEQUENTIAL";
export type MultiInstanceCompletionPolicy =
  "ALL" | "ANY" | "PERCENTAGE" | "QUORUM";
export type MultiInstanceRemainingItemPolicy = "CANCEL" | "ALLOW_COMPLETION";

export interface MultiInstanceConfig {
  collectionExpression: string;
  itemVariable: string;
  concurrency: MultiInstanceConcurrency;
  completionPolicy: MultiInstanceCompletionPolicy;
  completionPercentage?: number;
  quorumCount?: number;
  remainingItemPolicy: MultiInstanceRemainingItemPolicy;
}

export type JoinPolicy = "ALL" | "ANY";
export type JoinRemainingBranchPolicy = "CANCEL_REMAINING" | "AWAIT_COMPLETION";

export interface JoinConfig {
  policy: JoinPolicy; // Strict ALL / ANY only. N_OF_M strictly forbidden.
  joinScopeId?: string;
  remainingBranchPolicy: JoinRemainingBranchPolicy;
}

export type ReworkExhaustionBehavior = "FAIL_EVENT" | "ROUTE_ESCALATION";
export type ReworkRollbackStrategy = "KEEP_CURRENT" | "RESTORE_ORIGINAL";
export type MultiInstanceReworkScope = "CURRENT_ITEM" | "WHOLE_NODE";

export interface ReworkConfig {
  targetStepId: string;
  maxIterations: number;
  exhaustionBehavior: ReworkExhaustionBehavior;
  exhaustionPort?: string;
  rollbackStrategy: ReworkRollbackStrategy;
  multiInstanceScope?: MultiInstanceReworkScope;
}

/** Defaults used when a user connects a revision branch for the first time. */
export function createDefaultReworkConfig(targetStepId: string): ReworkConfig {
  return {
    targetStepId,
    maxIterations: 3,
    exhaustionBehavior: "FAIL_EVENT",
    rollbackStrategy: "KEEP_CURRENT",
    multiInstanceScope: "WHOLE_NODE",
  };
}

/** Converts the editor model into the backend's bounded rework policy shape. */
export function toReworkPolicy(config: ReworkConfig): Record<string, unknown> {
  const policy: Record<string, unknown> = {
    maxIterations: config.maxIterations,
    onExhausted: config.exhaustionBehavior,
    scope: config.multiInstanceScope ?? "WHOLE_NODE",
    allowParallelScopeReset: false,
  };
  if (config.exhaustionPort?.trim()) {
    policy.exhaustionPort = config.exhaustionPort.trim();
  }
  return policy;
}

/** Converts a persisted backend policy back into the editor model. */
export function fromReworkPolicy(
  targetStepId: string,
  policy: Record<string, unknown>,
  rollbackStrategy?: unknown,
): ReworkConfig {
  const maxIterations = Number(policy.maxIterations);
  return {
    targetStepId,
    maxIterations:
      Number.isFinite(maxIterations) && maxIterations > 0
        ? Math.floor(maxIterations)
        : 3,
    exhaustionBehavior:
      policy.onExhausted === "ROUTE_ESCALATION"
        ? "ROUTE_ESCALATION"
        : "FAIL_EVENT",
    exhaustionPort:
      typeof policy.exhaustionPort === "string"
        ? policy.exhaustionPort
        : undefined,
    rollbackStrategy:
      rollbackStrategy === "RESTORE_ORIGINAL"
        ? "RESTORE_ORIGINAL"
        : "KEEP_CURRENT",
    multiInstanceScope:
      policy.scope === "CURRENT_ITEM" ? "CURRENT_ITEM" : "WHOLE_NODE",
  };
}

export interface EdgeData extends Record<string, unknown> {
  priority?: number;
  isDefault?: boolean;
  transitionType?: EdgeTransitionType;
  condition?: Record<string, unknown> | string;
  reworkConfig?: ReworkConfig;
}
