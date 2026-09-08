export type FormFieldType =
  | "STRING"
  | "INTEGER"
  | "DECIMAL"
  | "BOOLEAN"
  | "DATE"
  | "ENUM"
  | "FILE";

export interface FormFieldDefinition {
  key: string;
  label: string;
  type: FormFieldType;
  required: boolean;
  options?: string[];
  description?: string;
  defaultValue?: unknown;
}

export interface FormSchema {
  fields: FormFieldDefinition[];
}

export type FieldDependencyType =
  | "TRANSITION_CONDITION"
  | "PARTICIPANT_EXPRESSION"
  | "TITLE_TEMPLATE"
  | "CHILD_WORKFLOW_MAPPING";

export interface FieldDependencyReference {
  type: FieldDependencyType;
  targetId: string;
  targetName: string;
  detail: string;
}
