"use client";

import { useId, useState } from "react";
import type { FormFieldDefinition, FormFieldType, FormSchema } from "../form-types";
import type { BuilderEdge, BuilderNode } from "../types";
import { findFieldDependencies } from "../utils/field-dependency";
import { FieldDependencyModal } from "./field-dependency-modal";

const FIELD_TYPES: { value: FormFieldType; label: string }[] = [
  { value: "STRING", label: "Text / String" },
  { value: "INTEGER", label: "Integer Number" },
  { value: "DECIMAL", label: "Decimal / Currency" },
  { value: "BOOLEAN", label: "Boolean / Switch" },
  { value: "DATE", label: "Date" },
  { value: "ENUM", label: "Dropdown / Enum" },
  { value: "FILE", label: "File Attachment" },
];

interface FormBuilderProps {
  schema?: FormSchema;
  onChange: (schema: FormSchema) => void;
  nodes?: BuilderNode[];
  edges?: BuilderEdge[];
  readOnly?: boolean;
  title?: string;
  description?: string;
}

export function FormBuilder({
  schema = { fields: [] },
  onChange,
  nodes = [],
  edges = [],
  readOnly = false,
  title = "Form Field Schema",
  description = "Define form input fields for this workflow step or request form.",
}: FormBuilderProps) {
  const fields = schema.fields || [];

  // Edit/Add modal or inline state
  const [editingIndex, setEditingIndex] = useState<number | null>(null);
  const [isAdding, setIsAdding] = useState(false);

  // Field form values
  const [fieldKey, setFieldKey] = useState("");
  const [fieldLabel, setFieldLabel] = useState("");
  const [fieldType, setFieldType] = useState<FormFieldType>("STRING");
  const [fieldRequired, setFieldRequired] = useState(false);
  const [fieldOptions, setFieldOptions] = useState("");
  const [fieldDesc, setFieldDesc] = useState("");
  const [formError, setFormError] = useState<string | null>(null);

  // Dependency modal state
  const [depModalOpen, setDepModalOpen] = useState(false);
  const [depActionType, setDepActionType] = useState<"DELETE" | "RENAME">("DELETE");
  const [targetFieldKey, setTargetFieldKey] = useState("");
  const [pendingAction, setPendingAction] = useState<(() => void) | null>(null);
  const [detectedDeps, setDetectedDeps] = useState<ReturnType<typeof findFieldDependencies>>([]);
  const formHtmlId = useId();

  const resetForm = () => {
    setFieldKey("");
    setFieldLabel("");
    setFieldType("STRING");
    setFieldRequired(false);
    setFieldOptions("");
    setFieldDesc("");
    setFormError(null);
    setEditingIndex(null);
    setIsAdding(false);
  };

  const startAdd = () => {
    resetForm();
    setIsAdding(true);
  };

  const startEdit = (index: number) => {
    const f = fields[index];
    setFieldKey(f.key);
    setFieldLabel(f.label);
    setFieldType(f.type);
    setFieldRequired(f.required);
    setFieldOptions(f.options ? f.options.join(", ") : "");
    setFieldDesc(f.description || "");
    setFormError(null);
    setEditingIndex(index);
    setIsAdding(false);
  };

  // Reorder
  const handleMove = (index: number, direction: "UP" | "DOWN") => {
    if (readOnly) return;
    const targetIndex = direction === "UP" ? index - 1 : index + 1;
    if (targetIndex < 0 || targetIndex >= fields.length) return;

    const nextFields = [...fields];
    const [moved] = nextFields.splice(index, 1);
    nextFields.splice(targetIndex, 0, moved);
    onChange({ fields: nextFields });
  };

  // Delete with dependency check
  const handleDelete = (index: number) => {
    if (readOnly) return;
    const target = fields[index];
    const deps = findFieldDependencies(target.key, nodes, edges);

    if (deps.length > 0) {
      setTargetFieldKey(target.key);
      setDetectedDeps(deps);
      setDepActionType("DELETE");
      setPendingAction(() => () => {
        const nextFields = fields.filter((_, idx) => idx !== index);
        onChange({ fields: nextFields });
        setDepModalOpen(false);
      });
      setDepModalOpen(true);
    } else {
      const nextFields = fields.filter((_, idx) => idx !== index);
      onChange({ fields: nextFields });
    }
  };

  // Save Add or Edit
  const handleSaveField = () => {
    const trimmedKey = fieldKey.trim();
    const trimmedLabel = fieldLabel.trim();

    if (!trimmedKey) {
      setFormError("Field identifier key is required.");
      return;
    }
    if (!/^[a-zA-Z0-9_]+$/.test(trimmedKey)) {
      setFormError("Key must contain only alphanumeric characters and underscores.");
      return;
    }
    if (!trimmedLabel) {
      setFormError("Field label is required.");
      return;
    }

    const optionsArray =
      fieldType === "ENUM"
        ? fieldOptions
            .split(",")
            .map((s) => s.trim())
            .filter(Boolean)
        : undefined;

    const newField: FormFieldDefinition = {
      key: trimmedKey,
      label: trimmedLabel,
      type: fieldType,
      required: fieldRequired,
      description: fieldDesc.trim() || undefined,
      options: optionsArray,
    };

    if (isAdding) {
      // Check duplicate key
      if (fields.some((f) => f.key === trimmedKey)) {
        setFormError(`A field with key '${trimmedKey}' already exists.`);
        return;
      }
      onChange({ fields: [...fields, newField] });
      resetForm();
    } else if (editingIndex !== null) {
      const oldField = fields[editingIndex];
      // Check duplicate key with other fields
      if (fields.some((f, idx) => idx !== editingIndex && f.key === trimmedKey)) {
        setFormError(`A field with key '${trimmedKey}' already exists.`);
        return;
      }

      // If key was renamed, check dependencies
      if (oldField.key !== trimmedKey) {
        const deps = findFieldDependencies(oldField.key, nodes, edges);
        if (deps.length > 0) {
          setTargetFieldKey(oldField.key);
          setDetectedDeps(deps);
          setDepActionType("RENAME");
          setPendingAction(() => () => {
            const nextFields = [...fields];
            nextFields[editingIndex] = newField;
            onChange({ fields: nextFields });
            setDepModalOpen(false);
            resetForm();
          });
          setDepModalOpen(true);
          return;
        }
      }

      const nextFields = [...fields];
      nextFields[editingIndex] = newField;
      onChange({ fields: nextFields });
      resetForm();
    }
  };

  return (
    <div className="space-y-4" data-testid="form-builder">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-slate-200 pb-2">
        <div>
          <h4 className="text-xs font-bold text-slate-900">{title}</h4>
          <p className="text-[11px] text-slate-500">{description}</p>
        </div>
        {!readOnly && !isAdding && editingIndex === null && (
          <button
            type="button"
            data-testid="add-form-field-btn"
            onClick={startAdd}
            className="inline-flex items-center gap-1 rounded bg-blue-600 px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-blue-700"
          >
            + Add Field
          </button>
        )}
      </div>

      {/* Field List */}
      {fields.length === 0 && !isAdding ? (
        <div
          data-testid="empty-form-fields-message"
          className="rounded-lg border border-dashed border-slate-300 p-4 text-center text-xs text-slate-400"
        >
          No form fields defined yet. Click &quot;+ Add Field&quot; to configure input parameters.
        </div>
      ) : (
        <div className="space-y-2">
          {fields.map((f, idx) => (
            <div
              key={f.key}
              data-testid={`form-field-row-${f.key}`}
              className="flex items-center justify-between rounded-lg border border-slate-200 bg-white p-2.5 shadow-2xs hover:border-slate-300"
            >
              <div className="flex items-center gap-3">
                {/* Reorder Buttons */}
                {!readOnly && (
                  <div className="flex flex-col gap-0.5">
                    <button
                      type="button"
                      data-testid={`field-move-up-${f.key}`}
                      disabled={idx === 0}
                      onClick={() => handleMove(idx, "UP")}
                      className="text-[10px] text-slate-400 hover:text-slate-800 disabled:opacity-20"
                      title="Move up"
                    >
                      ▲
                    </button>
                    <button
                      type="button"
                      data-testid={`field-move-down-${f.key}`}
                      disabled={idx === fields.length - 1}
                      onClick={() => handleMove(idx, "DOWN")}
                      className="text-[10px] text-slate-400 hover:text-slate-800 disabled:opacity-20"
                      title="Move down"
                    >
                      ▼
                    </button>
                  </div>
                )}

                <div>
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-xs font-bold text-slate-800">
                      {f.key}
                    </span>
                    <span className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-slate-600">
                      {f.type}
                    </span>
                    {f.required && (
                      <span className="rounded bg-rose-50 px-1.5 py-0.5 text-[10px] font-semibold text-rose-600">
                        Required
                      </span>
                    )}
                  </div>
                  <p className="text-[11px] text-slate-500">{f.label}</p>
                </div>
              </div>

              {/* Action Buttons */}
              {!readOnly && (
                <div className="flex items-center gap-1">
                  <button
                    type="button"
                    data-testid={`edit-field-${f.key}`}
                    onClick={() => startEdit(idx)}
                    className="rounded p-1 text-xs text-slate-500 hover:bg-slate-100 hover:text-slate-900"
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    data-testid={`delete-field-${f.key}`}
                    onClick={() => handleDelete(idx)}
                    className="rounded p-1 text-xs text-rose-600 hover:bg-rose-50"
                  >
                    Delete
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Inline Form for Add / Edit */}
      {(isAdding || editingIndex !== null) && (
        <div
          data-testid="field-editor-form"
          className="rounded-lg border border-blue-200 bg-blue-50/50 p-3.5 space-y-3"
        >
          <h5 className="text-xs font-bold text-blue-900">
            {isAdding ? "Add Form Field" : `Edit Field: ${fields[editingIndex!].key}`}
          </h5>

          {formError && (
            <div className="rounded bg-rose-100 p-2 text-xs text-rose-700">
              {formError}
            </div>
          )}

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label
                htmlFor={`${formHtmlId}-fieldKey`}
                className="text-[11px] font-semibold text-slate-700 block mb-0.5"
              >
                Field Identifier Key *
              </label>
              <input
                id={`${formHtmlId}-fieldKey`}
                type="text"
                data-testid="input-field-key"
                value={fieldKey}
                onChange={(e) => setFieldKey(e.target.value)}
                placeholder="e.g. totalAmount"
                className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 font-mono text-xs"
              />
            </div>
            <div>
              <label
                htmlFor={`${formHtmlId}-fieldLabel`}
                className="text-[11px] font-semibold text-slate-700 block mb-0.5"
              >
                Display Label *
              </label>
              <input
                id={`${formHtmlId}-fieldLabel`}
                type="text"
                data-testid="input-field-label"
                value={fieldLabel}
                onChange={(e) => setFieldLabel(e.target.value)}
                placeholder="e.g. Total Amount"
                className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label
                htmlFor={`${formHtmlId}-fieldType`}
                className="text-[11px] font-semibold text-slate-700 block mb-0.5"
              >
                Data Type
              </label>
              <select
                id={`${formHtmlId}-fieldType`}
                data-testid="select-field-type"
                value={fieldType}
                onChange={(e) => setFieldType(e.target.value as FormFieldType)}
                className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs"
              >
                {FIELD_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex items-center pt-4">
              <label className="flex items-center gap-2 text-xs font-semibold text-slate-700 cursor-pointer">
                <input
                  type="checkbox"
                  data-testid="checkbox-field-required"
                  checked={fieldRequired}
                  onChange={(e) => setFieldRequired(e.target.checked)}
                  className="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                />
                Required Field
              </label>
            </div>
          </div>

          {fieldType === "ENUM" && (
            <div>
              <label
                htmlFor={`${formHtmlId}-fieldOptions`}
                className="text-[11px] font-semibold text-slate-700 block mb-0.5"
              >
                Options (comma-separated)
              </label>
              <input
                id={`${formHtmlId}-fieldOptions`}
                type="text"
                data-testid="input-field-options"
                value={fieldOptions}
                onChange={(e) => setFieldOptions(e.target.value)}
                placeholder="Option A, Option B, Option C"
                className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs"
              />
            </div>
          )}

          <div>
            <label
              htmlFor={`${formHtmlId}-fieldDesc`}
              className="text-[11px] font-semibold text-slate-700 block mb-0.5"
            >
              Help Description
            </label>
            <input
              id={`${formHtmlId}-fieldDesc`}
              type="text"
              data-testid="input-field-description"
              value={fieldDesc}
              onChange={(e) => setFieldDesc(e.target.value)}
              placeholder="Optional user guidance"
              className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs"
            />
          </div>

          <div className="flex items-center justify-end gap-2 pt-1">
            <button
              type="button"
              data-testid="cancel-field-edit-btn"
              onClick={resetForm}
              className="rounded border border-slate-300 bg-white px-2.5 py-1 text-xs font-semibold text-slate-700 hover:bg-slate-50"
            >
              Cancel
            </button>
            <button
              type="button"
              data-testid="save-field-btn"
              onClick={handleSaveField}
              className="rounded bg-blue-600 px-3 py-1 text-xs font-semibold text-white hover:bg-blue-700"
            >
              Save Field
            </button>
          </div>
        </div>
      )}

      {/* Breaking Change Dependency Modal */}
      <FieldDependencyModal
        isOpen={depModalOpen}
        fieldKey={targetFieldKey}
        actionType={depActionType}
        dependencies={detectedDeps}
        onConfirm={() => {
          if (pendingAction) pendingAction();
        }}
        onCancel={() => {
          setDepModalOpen(false);
          setPendingAction(null);
        }}
      />
    </div>
  );
}
