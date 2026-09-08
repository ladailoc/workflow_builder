export interface CatalogItem {
  id: string;
  key: string;
  name: string;
  description: string;
  category: string;
}

export type CanonicalValueTypeName =
  | "STRING"
  | "NUMBER"
  | "INTEGER"
  | "DECIMAL"
  | "BOOLEAN"
  | "DATE"
  | "DATETIME"
  | "DATE_TIME"
  | "DURATION"
  | "MONEY"
  | "USER_ID"
  | "USER"
  | "DEPARTMENT_ID"
  | "GROUP_ID"
  | "ENUM"
  | "OBJECT"
  | "ARRAY"
  | "FILE_REF"
  | "FILE_LIST";

export interface TypeDescriptor {
  type: CanonicalValueTypeName;
  nullable?: boolean;
  itemType?: TypeDescriptor;
}

export type Expression =
  | {
      kind: "LITERAL";
      value: unknown;
      type: TypeDescriptor;
    }
  | {
      kind: "REFERENCE";
      path: string | { path: string };
    }
  | {
      kind: "OPERATOR";
      operator:
        | "EQ"
        | "NE"
        | "GT"
        | "GTE"
        | "LT"
        | "LTE"
        | "IN"
        | "CONTAINS"
        | "IS_NULL"
        | "AND"
        | "OR"
        | "NOT";
      operands: Expression[];
    };

export type RequirementMode = "ALWAYS" | "NEVER" | "CONDITIONAL";
export type VisibilityMode = "ALWAYS" | "NEVER" | "CONDITIONAL";
export type EditabilityMode = "EDITABLE" | "READ_ONLY" | "CONDITIONAL";

export interface FieldRequirement {
  mode: RequirementMode;
  condition?: Expression | null;
}

export interface FieldVisibility {
  mode: VisibilityMode;
  condition?: Expression | null;
}

export interface FieldEditability {
  mode: EditabilityMode;
  condition?: Expression | null;
}

export interface FieldValidationRules {
  minimum?: number | null;
  maximum?: number | null;
  minimumLength?: number | null;
  maximumLength?: number | null;
  regex?: { pattern: string } | null;
  safeRules?: Expression[];
}

export interface FieldOptions {
  source: "STATIC" | "DATA_SOURCE";
  staticValues?: unknown[];
  dataSourceKey?: string | null;
}

export interface FormFieldDefinition {
  fieldId: string;
  key: string;
  label: string;
  description?: string | null;
  placeholder?: string | null;
  order: number;
  type: TypeDescriptor;
  defaultValue?: unknown;
  sensitive: boolean;
  requirement: FieldRequirement;
  visibility: FieldVisibility;
  editability: FieldEditability;
  validation: FieldValidationRules;
  options?: FieldOptions | null;
  semantics?: Record<string, unknown> | null;
}

export interface FormSchema {
  formKey: string;
  formType: "TICKET_FORM" | "TASK_FORM";
  fields: FormFieldDefinition[];
}

export interface CreateSchemaResponse {
  requestTypeId: string;
  requestTypeKey: string;
  sourceWorkflowVersionId: string;
  formSchemaVersion: number;
  formSchemaChecksum: string;
  ticketFormSchema: FormSchema;
}

export interface TicketSubjectInput {
  subjectType: string;
  subjectRefId: string;
  roleKey: string;
  sourceField: string;
}

export interface CreateDraftPayload {
  requestTypeId: string;
  dataJson: Record<string, unknown>;
  subjects: TicketSubjectInput[];
}

export interface SubmitTicketPayload {
  sourceWorkflowVersionId: string;
  schemaChecksum: string;
  changeReason: string;
  expectedDataRevision: number;
}

export interface CreatedTicketAggregate {
  ticket: {
    id: string;
    requestTypeId: string;
    creatorId: string;
    status: string;
    dataJson: Record<string, unknown>;
    dataRevision: number;
    currentRevisionId: string | null;
    lockVersion: number;
    createdAt: string;
    updatedAt: string;
  };
  revisions: unknown[];
  subjects: unknown[];
}
