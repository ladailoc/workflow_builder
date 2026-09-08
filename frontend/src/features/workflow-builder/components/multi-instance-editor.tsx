"use client";

import { useId } from "react";
import type {
  MultiInstanceCompletionPolicy,
  MultiInstanceConcurrency,
  MultiInstanceConfig,
  MultiInstanceRemainingItemPolicy,
} from "../editor-types";

interface MultiInstanceEditorProps {
  value?: MultiInstanceConfig;
  onChange: (config: MultiInstanceConfig) => void;
  readOnly?: boolean;
}

export function MultiInstanceEditor({
  value,
  onChange,
  readOnly = false,
}: MultiInstanceEditorProps) {
  const formHtmlId = useId();

  const current: MultiInstanceConfig = value ?? {
    collectionExpression: "payload.items",
    itemVariable: "item",
    concurrency: "PARALLEL",
    completionPolicy: "ALL",
    remainingItemPolicy: "CANCEL",
  };

  const update = (updates: Partial<MultiInstanceConfig>) => {
    if (readOnly) return;
    onChange({ ...current, ...updates });
  };

  return (
    <div className="space-y-3" data-testid="multi-instance-editor">
      <div className="border-b border-slate-200 pb-1.5">
        <h4 className="text-xs font-bold text-slate-800">
          Multi-Instance Execution Configuration
        </h4>
        <p className="text-[11px] text-slate-500">
          Executes this step iteratively for each item in a target collection.
        </p>
      </div>

      <div className="grid grid-cols-2 gap-3">
        {/* Collection Expression */}
        <div>
          <label
            htmlFor={`${formHtmlId}-collectionExpr`}
            className="text-[11px] font-semibold text-slate-700 block mb-0.5"
          >
            Collection Expression *
          </label>
          <input
            id={`${formHtmlId}-collectionExpr`}
            type="text"
            data-testid="input-mi-collection-expression"
            disabled={readOnly}
            value={current.collectionExpression}
            placeholder="e.g. payload.lineItems"
            onChange={(e) => update({ collectionExpression: e.target.value })}
            className="w-full rounded border border-slate-300 px-2.5 py-1.5 font-mono text-xs disabled:bg-slate-100"
          />
        </div>

        {/* Item Variable Name */}
        <div>
          <label
            htmlFor={`${formHtmlId}-itemVar`}
            className="text-[11px] font-semibold text-slate-700 block mb-0.5"
          >
            Item Variable Name *
          </label>
          <input
            id={`${formHtmlId}-itemVar`}
            type="text"
            data-testid="input-mi-item-variable"
            disabled={readOnly}
            value={current.itemVariable}
            placeholder="e.g. item"
            onChange={(e) => update({ itemVariable: e.target.value })}
            className="w-full rounded border border-slate-300 px-2.5 py-1.5 font-mono text-xs disabled:bg-slate-100"
          />
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3">
        {/* Concurrency */}
        <div>
          <label
            htmlFor={`${formHtmlId}-concurrency`}
            className="text-[11px] font-semibold text-slate-700 block mb-0.5"
          >
            Concurrency Mode
          </label>
          <select
            id={`${formHtmlId}-concurrency`}
            data-testid="select-mi-concurrency"
            disabled={readOnly}
            value={current.concurrency}
            onChange={(e) =>
              update({ concurrency: e.target.value as MultiInstanceConcurrency })
            }
            className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
          >
            <option value="PARALLEL">PARALLEL (Simultaneous instances)</option>
            <option value="SEQUENTIAL">SEQUENTIAL (One item at a time)</option>
          </select>
        </div>

        {/* Remaining Item Policy */}
        <div>
          <label
            htmlFor={`${formHtmlId}-remainingPolicy`}
            className="text-[11px] font-semibold text-slate-700 block mb-0.5"
          >
            Remaining Item Policy
          </label>
          <select
            id={`${formHtmlId}-remainingPolicy`}
            data-testid="select-mi-remaining-policy"
            disabled={readOnly}
            value={current.remainingItemPolicy}
            onChange={(e) =>
              update({
                remainingItemPolicy: e.target
                  .value as MultiInstanceRemainingItemPolicy,
              })
            }
            className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
          >
            <option value="CANCEL">CANCEL (Abort remaining items)</option>
            <option value="ALLOW_COMPLETION">
              ALLOW_COMPLETION (Let ongoing items finish)
            </option>
          </select>
        </div>
      </div>

      {/* Completion Policy */}
      <div>
        <label
          htmlFor={`${formHtmlId}-completionPolicy`}
          className="text-[11px] font-semibold text-slate-700 block mb-0.5"
        >
          Completion Policy
        </label>
        <select
          id={`${formHtmlId}-completionPolicy`}
          data-testid="select-mi-completion-policy"
          disabled={readOnly}
          value={current.completionPolicy}
          onChange={(e) =>
            update({
              completionPolicy: e.target
                .value as MultiInstanceCompletionPolicy,
            })
          }
          className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
        >
          <option value="ALL">ALL (All items must complete)</option>
          <option value="ANY">ANY (First completed item completes step)</option>
          <option value="PERCENTAGE">PERCENTAGE (Percentage threshold)</option>
          <option value="QUORUM">QUORUM (Fixed number of completions)</option>
        </select>
      </div>

      {current.completionPolicy === "PERCENTAGE" && (
        <div>
          <label
            htmlFor={`${formHtmlId}-completionPercent`}
            className="text-[11px] font-semibold text-slate-700 block mb-0.5"
          >
            Completion Percentage (%)
          </label>
          <input
            id={`${formHtmlId}-completionPercent`}
            type="number"
            min="1"
            max="100"
            data-testid="input-mi-completion-percentage"
            disabled={readOnly}
            value={current.completionPercentage ?? 50}
            onChange={(e) =>
              update({
                completionPercentage: parseInt(e.target.value || "50", 10),
              })
            }
            className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
          />
        </div>
      )}

      {current.completionPolicy === "QUORUM" && (
        <div>
          <label
            htmlFor={`${formHtmlId}-quorumCount`}
            className="text-[11px] font-semibold text-slate-700 block mb-0.5"
          >
            Quorum Count (Items)
          </label>
          <input
            id={`${formHtmlId}-quorumCount`}
            type="number"
            min="1"
            max="100"
            data-testid="input-mi-quorum-count"
            disabled={readOnly}
            value={current.quorumCount ?? 2}
            onChange={(e) =>
              update({
                quorumCount: parseInt(e.target.value || "2", 10),
              })
            }
            className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
          />
        </div>
      )}
    </div>
  );
}
