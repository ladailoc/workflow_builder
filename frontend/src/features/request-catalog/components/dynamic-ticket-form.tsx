"use client";

import { useId, useState } from "react";
import { useRouter } from "next/navigation";
import { evaluateExpression } from "../expression-evaluator";
import { createTicketDraft, fetchCreateSchema, submitTicket } from "../api";
import type {
  CreateSchemaResponse,
  FormFieldDefinition,
} from "../types";
import { ApiRequestError } from "@/shared/api/client";

interface DynamicTicketFormProps {
  initialSchema: CreateSchemaResponse;
  requestTypeKey: string;
}

export function DynamicTicketForm({
  initialSchema,
  requestTypeKey,
}: DynamicTicketFormProps) {
  const router = useRouter();
  const formHtmlId = useId();

  // Schema state (can be updated on concurrent publish reload)
  const [schemaData, setSchemaData] = useState<CreateSchemaResponse>(initialSchema);
  const [formData, setFormData] = useState<Record<string, unknown>>(() => {
    const initialValues: Record<string, unknown> = {};
    initialSchema.ticketFormSchema.fields.forEach((f) => {
      if (f.defaultValue !== undefined && f.defaultValue !== null) {
        initialValues[f.key] = f.defaultValue;
      }
    });
    return initialValues;
  });

  // Track newly required fields after schema reload
  const [newlyRequiredKeys, setNewlyRequiredKeys] = useState<Set<string>>(new Set());

  // Password / sensitive mask state
  const [revealedSensitive, setRevealedSensitive] = useState<Record<string, boolean>>({});

  // Validation errors
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  // Schema mismatch state
  const [schemaMismatch, setSchemaMismatch] = useState<boolean>(false);
  const [reloadingSchema, setReloadingSchema] = useState<boolean>(false);

  const fields = schemaData.ticketFormSchema.fields;

  // Evaluate dynamic visibility
  const isFieldVisible = (field: FormFieldDefinition): boolean => {
    if (field.visibility.mode === "ALWAYS") return true;
    if (field.visibility.mode === "NEVER") return false;
    if (field.visibility.mode === "CONDITIONAL" && field.visibility.condition) {
      return Boolean(evaluateExpression(field.visibility.condition, formData));
    }
    return true;
  };

  // Evaluate dynamic requirement
  const isFieldRequired = (field: FormFieldDefinition): boolean => {
    if (field.requirement.mode === "ALWAYS") return true;
    if (field.requirement.mode === "NEVER") return false;
    if (field.requirement.mode === "CONDITIONAL" && field.requirement.condition) {
      return Boolean(evaluateExpression(field.requirement.condition, formData));
    }
    return false;
  };

  // Evaluate editability
  const isFieldEditable = (field: FormFieldDefinition): boolean => {
    if (field.editability.mode === "EDITABLE") return true;
    if (field.editability.mode === "READ_ONLY") return false;
    if (field.editability.mode === "CONDITIONAL" && field.editability.condition) {
      return Boolean(evaluateExpression(field.editability.condition, formData));
    }
    return true;
  };

  const handleFieldChange = (key: string, value: unknown) => {
    setFormData((prev) => ({ ...prev, [key]: value }));
    if (errors[key]) {
      setErrors((prev) => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
    }
    // clear newly required highlight once filled
    if (newlyRequiredKeys.has(key)) {
      setNewlyRequiredKeys((prev) => {
        const next = new Set(prev);
        next.delete(key);
        return next;
      });
    }
  };

  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};

    for (const field of fields) {
      if (!isFieldVisible(field)) continue;

      const required = isFieldRequired(field);
      const val = formData[field.key];

      if (required) {
        if (
          val === undefined ||
          val === null ||
          val === "" ||
          (Array.isArray(val) && val.length === 0)
        ) {
          newErrors[field.key] = `${field.label} is required`;
          continue;
        }
      }

      // Check min / max for numbers
      if (val !== undefined && val !== null && val !== "") {
        if (
          (field.type.type === "INTEGER" ||
            field.type.type === "NUMBER" ||
            field.type.type === "DECIMAL") &&
          typeof val === "number"
        ) {
          if (
            field.validation.minimum !== null &&
            field.validation.minimum !== undefined &&
            val < field.validation.minimum
          ) {
            newErrors[field.key] = `${field.label} must be at least ${field.validation.minimum}`;
          } else if (
            field.validation.maximum !== null &&
            field.validation.maximum !== undefined &&
            val > field.validation.maximum
          ) {
            newErrors[field.key] = `${field.label} must be at most ${field.validation.maximum}`;
          }
        }

        // Check string length & regex
        if (typeof val === "string") {
          if (
            field.validation.minimumLength !== null &&
            field.validation.minimumLength !== undefined &&
            val.length < field.validation.minimumLength
          ) {
            newErrors[field.key] = `${field.label} must be at least ${field.validation.minimumLength} characters`;
          } else if (
            field.validation.maximumLength !== null &&
            field.validation.maximumLength !== undefined &&
            val.length > field.validation.maximumLength
          ) {
            newErrors[field.key] = `${field.label} must be at most ${field.validation.maximumLength} characters`;
          } else if (field.validation.regex?.pattern) {
            try {
              const rx = new RegExp(field.validation.regex.pattern);
              if (!rx.test(val)) {
                newErrors[field.key] = `${field.label} format is invalid`;
              }
            } catch {
              // Ignore invalid regex in client
            }
          }
        }
      }
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleReloadSchema = async () => {
    setReloadingSchema(true);
    setSubmitError(null);
    try {
      const latest = await fetchCreateSchema(requestTypeKey);
      
      // Calculate newly required fields
      const previousRequired = new Set(
        fields.filter(isFieldRequired).map((f) => f.key),
      );
      const newlyReq = new Set<string>();
      latest.ticketFormSchema.fields.forEach((f) => {
        if (
          f.requirement.mode === "ALWAYS" &&
          !previousRequired.has(f.key) &&
          !formData[f.key]
        ) {
          newlyReq.add(f.key);
        }
      });

      // Preserve compatible filled values
      const preservedData: Record<string, unknown> = {};
      latest.ticketFormSchema.fields.forEach((f) => {
        if (formData[f.key] !== undefined) {
          preservedData[f.key] = formData[f.key];
        } else if (f.defaultValue !== undefined && f.defaultValue !== null) {
          preservedData[f.key] = f.defaultValue;
        }
      });

      setSchemaData(latest);
      setFormData(preservedData);
      setNewlyRequiredKeys(newlyReq);
      setSchemaMismatch(false);
    } catch (err: unknown) {
      setSubmitError(
        err instanceof Error ? err.message : "Failed to reload updated form schema",
      );
    } finally {
      setReloadingSchema(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitError(null);

    if (!validateForm()) {
      return;
    }

    setSubmitting(true);

    try {
      // 1. Create Draft
      const draftResult = await createTicketDraft({
        requestTypeId: schemaData.requestTypeId,
        dataJson: formData,
        subjects: [],
      });

      const ticketId = draftResult.ticket.id;
      const lockVersion = draftResult.ticket.lockVersion;

      // 2. Submit Draft with pinned schema checksum and workflow version
      await submitTicket(ticketId, lockVersion, {
        sourceWorkflowVersionId: schemaData.sourceWorkflowVersionId,
        schemaChecksum: schemaData.formSchemaChecksum,
        changeReason: "Initial ticket submission",
        expectedDataRevision: draftResult.ticket.dataRevision,
      });

      // Redirect to ticket details upon success
      router.push(`/tickets/${ticketId}`);
    } catch (err: unknown) {
      if (err instanceof ApiRequestError) {
        if (
          err.code === "FORM_SCHEMA_CHANGED" ||
          err.code === "TICKET_SCHEMA_OUTDATED" ||
          err.message.includes("FORM_SCHEMA_CHANGED") ||
          err.message.includes("TICKET_SCHEMA_OUTDATED")
        ) {
          setSchemaMismatch(true);
          setSubmitError(
            "The request form definition has been updated by an administrator. Please reload the latest schema before submitting.",
          );
          setSubmitting(false);
          return;
        }
      }
      setSubmitError(
        err instanceof Error
          ? err.message
          : "An unexpected error occurred while submitting the request",
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-6 max-w-3xl">
      {/* Concurrent Publish / Schema Mismatch Alert */}
      {schemaMismatch && (
        <div
          data-testid="schema-mismatch-banner"
          className="rounded-xl border border-amber-300 bg-amber-50 p-4 text-amber-900 shadow-xs"
        >
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-start gap-3">
              <svg
                className="h-5 w-5 text-amber-600 mt-0.5 shrink-0"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
                />
              </svg>
              <div>
                <h4 className="text-sm font-semibold text-amber-900">
                  Form Schema Updated
                </h4>
                <p className="mt-1 text-xs text-amber-800">
                  An administrator published a new version of this workflow while you
                  were editing. Your compatible answers will be preserved.
                </p>
              </div>
            </div>
            <button
              type="button"
              data-testid="reload-schema-button"
              disabled={reloadingSchema}
              onClick={handleReloadSchema}
              className="shrink-0 rounded-lg bg-amber-600 px-3 py-1.5 text-xs font-semibold text-white shadow-2xs hover:bg-amber-700 disabled:opacity-50"
            >
              {reloadingSchema ? "Reloading..." : "Reload Schema"}
            </button>
          </div>
        </div>
      )}

      {/* General Submission Error */}
      {submitError && !schemaMismatch && (
        <div
          data-testid="form-submit-error"
          className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-xs text-rose-800"
        >
          <div className="flex items-center gap-2">
            <svg
              className="h-4 w-4 text-rose-500 shrink-0"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
              />
            </svg>
            <span className="font-medium">{submitError}</span>
          </div>
        </div>
      )}

      <form
        data-testid="dynamic-ticket-form"
        onSubmit={handleSubmit}
        className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs space-y-6"
      >
        <div className="border-b border-slate-100 pb-4">
          <h2 className="text-lg font-semibold text-slate-900">
            Request Information
          </h2>
          <p className="text-xs text-slate-500 mt-1">
            Please fill out all required fields marked with an asterisk (*).
          </p>
        </div>

        <div className="space-y-5">
          {fields.map((field) => {
            if (!isFieldVisible(field)) {
              return null;
            }

            const required = isFieldRequired(field);
            const editable = isFieldEditable(field);
            const error = errors[field.key];
            const isNewlyRequired = newlyRequiredKeys.has(field.key);
            const value = formData[field.key];
            const isSensitive = field.sensitive;
            const isRevealed = revealedSensitive[field.key] ?? false;

            return (
              <div
                key={field.key}
                data-testid={`form-field-${field.key}`}
                className={`space-y-1.5 rounded-lg p-2.5 transition-colors ${
                  isNewlyRequired ? "bg-amber-50/60 border border-amber-200" : ""
                }`}
              >
                <div className="flex items-center justify-between">
                  <label
                    htmlFor={`${formHtmlId}-${field.key}`}
                    className="flex items-center gap-1.5 text-xs font-semibold text-slate-700"
                  >
                    <span>{field.label}</span>
                    {required && <span className="text-rose-500 font-bold">*</span>}
                    {isNewlyRequired && (
                      <span
                        data-testid={`newly-required-badge-${field.key}`}
                        className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-semibold text-amber-800"
                      >
                        Newly Required
                      </span>
                    )}
                  </label>
                  {isSensitive && (
                    <button
                      type="button"
                      data-testid={`toggle-sensitive-${field.key}`}
                      onClick={() =>
                        setRevealedSensitive((prev) => ({
                          ...prev,
                          [field.key]: !isRevealed,
                        }))
                      }
                      className="text-[11px] font-medium text-slate-500 hover:text-slate-800"
                    >
                      {isRevealed ? "Hide" : "Show"}
                    </button>
                  )}
                </div>

                {field.description && (
                  <p className="text-[11px] text-slate-400">
                    {field.description}
                  </p>
                )}

                {/* Render Control based on type and options */}
                <div>
                  {/* Select Options */}
                  {field.options &&
                  field.options.source === "STATIC" &&
                  field.options.staticValues &&
                  field.options.staticValues.length > 0 ? (
                    <select
                      id={`${formHtmlId}-${field.key}`}
                      data-testid={`field-input-${field.key}`}
                      disabled={!editable}
                      value={String(value ?? "")}
                      onChange={(e) => handleFieldChange(field.key, e.target.value)}
                      className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800 shadow-2xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-slate-100 disabled:text-slate-400"
                    >
                      <option value="">-- Select an option --</option>
                      {field.options.staticValues.map((opt) => {
                        const optVal =
                          typeof opt === "object" && opt !== null
                            ? (opt as Record<string, unknown>).value ?? JSON.stringify(opt)
                            : String(opt);
                        const optLabel =
                          typeof opt === "object" && opt !== null
                            ? (opt as Record<string, unknown>).label ?? optVal
                            : String(opt);
                        return (
                          <option key={String(optVal)} value={String(optVal)}>
                            {String(optLabel)}
                          </option>
                        );
                      })}
                    </select>
                  ) : field.type.type === "BOOLEAN" ? (
                    /* Boolean Toggle */
                    <label className="flex items-center gap-2.5 cursor-pointer pt-1">
                      <input
                        id={`${formHtmlId}-${field.key}`}
                        type="checkbox"
                        data-testid={`field-input-${field.key}`}
                        disabled={!editable}
                        checked={Boolean(value)}
                        onChange={(e) =>
                          handleFieldChange(field.key, e.target.checked)
                        }
                        className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500 disabled:opacity-50"
                      />
                      <span className="text-xs text-slate-600">
                        {Boolean(value) ? "Yes" : "No"}
                      </span>
                    </label>
                  ) : field.type.type === "INTEGER" ? (
                    /* Integer Number */
                    <input
                      id={`${formHtmlId}-${field.key}`}
                      type="number"
                      step="1"
                      data-testid={`field-input-${field.key}`}
                      disabled={!editable}
                      placeholder={field.placeholder ?? ""}
                      value={value !== undefined && value !== null ? String(value) : ""}
                      onChange={(e) =>
                        handleFieldChange(
                          field.key,
                          e.target.value === "" ? null : parseInt(e.target.value, 10),
                        )
                      }
                      className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800 shadow-2xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-slate-100"
                    />
                  ) : field.type.type === "DECIMAL" ||
                    field.type.type === "NUMBER" ||
                    field.type.type === "MONEY" ? (
                    /* Decimal / Number */
                    <input
                      id={`${formHtmlId}-${field.key}`}
                      type="number"
                      step="any"
                      data-testid={`field-input-${field.key}`}
                      disabled={!editable}
                      placeholder={field.placeholder ?? ""}
                      value={value !== undefined && value !== null ? String(value) : ""}
                      onChange={(e) =>
                        handleFieldChange(
                          field.key,
                          e.target.value === "" ? null : parseFloat(e.target.value),
                        )
                      }
                      className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800 shadow-2xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-slate-100"
                    />
                  ) : field.type.type === "DATE" ? (
                    /* Date */
                    <input
                      id={`${formHtmlId}-${field.key}`}
                      type="date"
                      data-testid={`field-input-${field.key}`}
                      disabled={!editable}
                      value={String(value ?? "")}
                      onChange={(e) => handleFieldChange(field.key, e.target.value)}
                      className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800 shadow-2xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-slate-100"
                    />
                  ) : field.type.type === "DATETIME" ||
                    field.type.type === "DATE_TIME" ? (
                    /* DateTime */
                    <input
                      id={`${formHtmlId}-${field.key}`}
                      type="datetime-local"
                      data-testid={`field-input-${field.key}`}
                      disabled={!editable}
                      value={String(value ?? "")}
                      onChange={(e) => handleFieldChange(field.key, e.target.value)}
                      className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800 shadow-2xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-slate-100"
                    />
                  ) : field.type.type === "FILE_REF" ||
                    field.type.type === "FILE_LIST" ? (
                    /* File Attachment */
                    <div className="flex items-center gap-3">
                      <input
                        id={`${formHtmlId}-${field.key}`}
                        type="text"
                        data-testid={`field-input-${field.key}`}
                        disabled={!editable}
                        placeholder={field.placeholder ?? "Enter attachment reference or URI"}
                        value={String(value ?? "")}
                        onChange={(e) => handleFieldChange(field.key, e.target.value)}
                        className="flex-1 rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800 shadow-2xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-slate-100"
                      />
                      <span className="rounded-md bg-slate-100 px-2.5 py-1.5 text-xs font-medium text-slate-600">
                        Attachment
                      </span>
                    </div>
                  ) : field.type.type === "ARRAY" ? (
                    /* Array / List Input */
                    <div className="space-y-2">
                      {Array.isArray(value) &&
                        value.map((item, idx) => (
                          <div key={idx} className="flex items-center gap-2">
                            <input
                              type="text"
                              data-testid={`array-item-${field.key}-${idx}`}
                              value={String(item ?? "")}
                              disabled={!editable}
                              onChange={(e) => {
                                const newArr = [...value];
                                newArr[idx] = e.target.value;
                                handleFieldChange(field.key, newArr);
                              }}
                              className="flex-1 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-800 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-slate-100"
                            />
                            {editable && (
                              <button
                                type="button"
                                data-testid={`remove-array-item-${field.key}-${idx}`}
                                onClick={() => {
                                  const newArr = value.filter((_, i) => i !== idx);
                                  handleFieldChange(field.key, newArr);
                                }}
                                className="rounded p-1.5 text-slate-400 hover:text-rose-600"
                              >
                                ✕
                              </button>
                            )}
                          </div>
                        ))}
                      {editable && (
                        <button
                          type="button"
                          data-testid={`add-array-item-${field.key}`}
                          onClick={() => {
                            const cur = Array.isArray(value) ? value : [];
                            handleFieldChange(field.key, [...cur, ""]);
                          }}
                          className="inline-flex items-center gap-1 rounded-md border border-slate-300 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
                        >
                          + Add Item
                        </button>
                      )}
                    </div>
                  ) : field.type.type === "OBJECT" ? (
                    /* Object / JSON */
                    <textarea
                      id={`${formHtmlId}-${field.key}`}
                      rows={3}
                      data-testid={`field-input-${field.key}`}
                      disabled={!editable}
                      value={
                        typeof value === "object" && value !== null
                          ? JSON.stringify(value, null, 2)
                          : String(value ?? "")
                      }
                      onChange={(e) => {
                        try {
                          const parsed = JSON.parse(e.target.value);
                          handleFieldChange(field.key, parsed);
                        } catch {
                          handleFieldChange(field.key, e.target.value);
                        }
                      }}
                      className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 font-mono text-xs text-slate-800 shadow-2xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-slate-100"
                    />
                  ) : (
                    /* Standard String / Text Input */
                    <input
                      id={`${formHtmlId}-${field.key}`}
                      type={isSensitive && !isRevealed ? "password" : "text"}
                      data-testid={`field-input-${field.key}`}
                      disabled={!editable}
                      placeholder={field.placeholder ?? ""}
                      value={String(value ?? "")}
                      onChange={(e) => handleFieldChange(field.key, e.target.value)}
                      className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-800 shadow-2xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-slate-100"
                    />
                  )}
                </div>

                {/* Field-level error message */}
                {error && (
                  <p
                    data-testid={`field-error-${field.key}`}
                    className="text-xs font-medium text-rose-600"
                  >
                    {error}
                  </p>
                )}
              </div>
            );
          })}
        </div>

        {/* Submit Actions */}
        <div className="flex items-center justify-end gap-3 border-t border-slate-100 pt-4">
          <button
            type="button"
            onClick={() => router.back()}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="submit"
            data-testid="submit-ticket-button"
            disabled={submitting || schemaMismatch}
            className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2 text-xs font-semibold text-white shadow-2xs hover:bg-blue-700 disabled:opacity-50"
          >
            {submitting ? (
              <>
                <svg
                  className="h-3.5 w-3.5 animate-spin text-white"
                  fill="none"
                  viewBox="0 0 24 24"
                >
                  <circle
                    className="opacity-25"
                    cx="12"
                    cy="12"
                    r="10"
                    stroke="currentColor"
                    strokeWidth="4"
                  />
                  <path
                    className="opacity-75"
                    fill="currentColor"
                    d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                  />
                </svg>
                <span>Submitting...</span>
              </>
            ) : (
              <span>Submit Request</span>
            )}
          </button>
        </div>
      </form>
    </div>
  );
}
