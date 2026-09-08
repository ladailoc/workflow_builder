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
export type MultiInstanceCompletionPolicy = "ALL" | "ANY" | "PERCENTAGE" | "QUORUM";
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
  rollbackStrategy: ReworkRollbackStrategy;
  multiInstanceScope?: MultiInstanceReworkScope;
}

export interface EdgeData extends Record<string, unknown> {
  priority?: number;
  isDefault?: boolean;
  transitionType?: EdgeTransitionType;
  condition?: Record<string, unknown> | string;
  reworkConfig?: ReworkConfig;
}
