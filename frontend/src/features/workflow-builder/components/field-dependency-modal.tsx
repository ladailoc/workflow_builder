"use client";

import type { FieldDependencyReference } from "../form-types";

interface FieldDependencyModalProps {
  isOpen: boolean;
  fieldKey: string;
  actionType: "DELETE" | "RENAME";
  dependencies: FieldDependencyReference[];
  onConfirm: () => void;
  onCancel: () => void;
}

export function FieldDependencyModal({
  isOpen,
  fieldKey,
  actionType,
  dependencies,
  onConfirm,
  onCancel,
}: FieldDependencyModalProps) {
  if (!isOpen) return null;

  const actionLabel = actionType === "DELETE" ? "Delete" : "Rename";

  return (
    <div
      role="dialog"
      aria-modal="true"
      data-testid="field-dependency-modal"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-xs p-4"
    >
      <div className="w-full max-w-lg rounded-xl bg-white p-6 shadow-2xl space-y-4 animate-scale-in">
        {/* Header */}
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-rose-100 text-rose-600 font-bold text-lg">
            !
          </div>
          <div>
            <h3 className="text-sm font-bold text-slate-900">
              Breaking Change Detected: Field &apos;{fieldKey}&apos;
            </h3>
            <p className="text-xs text-slate-500 mt-0.5">
              This field is referenced by {dependencies.length} workflow component{dependencies.length > 1 ? "s" : ""}.
              Modifying or deleting it may break graph evaluation.
            </p>
          </div>
        </div>

        {/* Dependency Reference List */}
        <div className="max-h-60 overflow-y-auto rounded-lg border border-slate-200 bg-slate-50 p-3 space-y-2">
          {dependencies.map((dep, idx) => (
            <div
              key={`${dep.targetId}-${dep.type}-${idx}`}
              data-testid="dependency-item"
              className="rounded border border-slate-200 bg-white p-2.5 text-xs space-y-1 shadow-2xs"
            >
              <div className="flex items-center justify-between">
                <span className="font-semibold text-slate-800">
                  {dep.targetName}
                </span>
                <span className="rounded bg-rose-50 px-2 py-0.5 font-mono text-[10px] font-bold text-rose-700">
                  {dep.type.replace(/_/g, " ")}
                </span>
              </div>
              <p className="text-[11px] text-slate-500">{dep.detail}</p>
            </div>
          ))}
        </div>

        {/* Action Controls */}
        <div className="flex items-center justify-end gap-2 pt-2 border-t border-slate-100">
          <button
            type="button"
            data-testid="cancel-dependency-action-btn"
            onClick={onCancel}
            className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            data-testid="confirm-dependency-action-btn"
            onClick={onConfirm}
            className="rounded-lg bg-rose-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-rose-700 shadow-xs"
          >
            Force {actionLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
