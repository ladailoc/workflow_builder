"use client";

import { useId } from "react";
import type { JoinConfig, JoinPolicy, JoinRemainingBranchPolicy } from "../editor-types";

interface JoinEditorProps {
  value?: JoinConfig;
  onChange: (config: JoinConfig) => void;
  readOnly?: boolean;
}

export function JoinEditor({
  value,
  onChange,
  readOnly = false,
}: JoinEditorProps) {
  const formHtmlId = useId();

  const current: JoinConfig = value ?? {
    policy: "ALL",
    joinScopeId: "",
    remainingBranchPolicy: "CANCEL_REMAINING",
  };

  const update = (updates: Partial<JoinConfig>) => {
    if (readOnly) return;
    onChange({ ...current, ...updates });
  };

  return (
    <div className="space-y-4" data-testid="join-editor">
      <div className="border-b border-slate-200 pb-1.5">
        <h4 className="text-xs font-bold text-slate-800">
          Join Execution Policy
        </h4>
        <p className="text-[11px] text-slate-500">
          Configures synchronization behavior for converging execution branches.
        </p>
      </div>

      {/* Execution Policy: STRICTLY ALL or ANY, no unsupported N_OF_M */}
      <div className="space-y-1">
        <label
          htmlFor={`${formHtmlId}-joinPolicy`}
          className="text-xs font-semibold text-slate-700 block"
        >
          Execution Policy *
        </label>
        <select
          id={`${formHtmlId}-joinPolicy`}
          data-testid="select-join-execution-policy"
          disabled={readOnly}
          value={current.policy}
          onChange={(e) => update({ policy: e.target.value as JoinPolicy })}
          className="w-full rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs text-slate-800 disabled:bg-slate-100 font-semibold"
        >
          <option value="ALL">ALL (Wait for all inbound branches to arrive)</option>
          <option value="ANY">ANY (First arriving branch triggers activation)</option>
        </select>
        <p className="text-[11px] text-slate-400">
          Engine strictly enforces ALL and ANY synchronization semantics.
        </p>
      </div>

      {/* Scope Identifier */}
      <div className="space-y-1">
        <label
          htmlFor={`${formHtmlId}-joinScopeId`}
          className="text-xs font-semibold text-slate-700 block"
        >
          Branch / Scope Identifier
        </label>
        <input
          id={`${formHtmlId}-joinScopeId`}
          type="text"
          data-testid="input-join-scope-id"
          disabled={readOnly}
          value={current.joinScopeId ?? ""}
          placeholder="e.g. parallel_review_scope"
          onChange={(e) => update({ joinScopeId: e.target.value })}
          className="w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-xs disabled:bg-slate-100"
        />
        <p className="text-[11px] text-slate-400">
          Optional scope key matching parallel split region.
        </p>
      </div>

      {/* Remaining Branch Policy */}
      <div className="space-y-1">
        <label
          htmlFor={`${formHtmlId}-remainingBranchPolicy`}
          className="text-xs font-semibold text-slate-700 block"
        >
          Remaining Branch Policy
        </label>
        <select
          id={`${formHtmlId}-remainingBranchPolicy`}
          data-testid="select-join-remaining-policy"
          disabled={readOnly}
          value={current.remainingBranchPolicy}
          onChange={(e) =>
            update({
              remainingBranchPolicy: e.target
                .value as JoinRemainingBranchPolicy,
            })
          }
          className="w-full rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs disabled:bg-slate-100"
        >
          <option value="CANCEL_REMAINING">
            CANCEL_REMAINING (Cancel uncompleted concurrent branches)
          </option>
          <option value="AWAIT_COMPLETION">
            AWAIT_COMPLETION (Let ongoing branches finish silently)
          </option>
        </select>
      </div>
    </div>
  );
}
