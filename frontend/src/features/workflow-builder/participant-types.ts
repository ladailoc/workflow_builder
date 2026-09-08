export type GenericResolverType =
  | "FIXED_USER"
  | "CREATOR"
  | "MANAGER_OF"
  | "HEAD_OF_UNIT"
  | "ITEM_MANAGER"
  | "ITEM_USER";

export type FriendlyResolverKind =
  | "FIXED_USER"
  | "CREATOR"
  | "CREATORS_MANAGER"
  | "MANAGER_N_LEVELS_UP"
  | "DEPARTMENT_HEAD"
  | "ITEM_MANAGER"
  | "ITEM_USER"
  | "REQUEST_FIELD"
  | "ROLE"
  | "GROUP"
  | "PREVIOUS_PARTICIPANT"
  | "NODE_OUTPUT"
  | "EXPRESSION";

export type ParticipantCardinality = "SINGLE" | "MULTI";

export type TaskGenerationMode = "ONE_PER_PARTICIPANT" | "SINGLE_CLAIMABLE";

export type CompletionPolicy =
  | "ALL_MUST_APPROVE"
  | "FIRST_RESPONSE"
  | "PERCENTAGE"
  | "QUORUM";

export interface FriendlyParticipantConfig {
  kind: FriendlyResolverKind;
  userId?: string;
  depth?: number;
  role?: string;
  group?: string;
  fieldKey?: string;
  stepId?: string;
  expression?: string;
  cardinality?: ParticipantCardinality;
  taskGenerationMode?: TaskGenerationMode;
  completionPolicy?: CompletionPolicy;
  completionPercentage?: number;
  quorumCount?: number;
  fallbackChain?: FriendlyParticipantConfig[];
}

export interface CompiledResolverPrimitive {
  type: GenericResolverType;
  userId?: string;
  depth?: number;
  role?: string;
  group?: string;
  field?: string;
  stepId?: string;
  expression?: string;
  [key: string]: unknown;
}

export interface CompiledParticipantConfig extends CompiledResolverPrimitive {
  cardinality: ParticipantCardinality;
  taskGenerationMode: TaskGenerationMode;
  completionPolicy: CompletionPolicy;
  completionPercentage?: number;
  quorumCount?: number;
  fallbackChain?: CompiledResolverPrimitive[];
}
