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
          Quy tắc hợp nhất
        </h4>
      </div>

      {/* Execution Policy: STRICTLY ALL or ANY, no unsupported N_OF_M */}
      <div className="space-y-1">
        <label
          htmlFor={`${formHtmlId}-joinPolicy`}
          className="text-xs font-semibold text-slate-700 block"
        >
          Quy tắc thực thi *
        </label>
        <select
          id={`${formHtmlId}-joinPolicy`}
          data-testid="select-join-execution-policy"
          disabled={readOnly}
          value={current.policy}
          onChange={(e) => update({ policy: e.target.value as JoinPolicy })}
          className="w-full rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs text-slate-800 disabled:bg-slate-100 font-semibold"
        >
          <option value="ALL">ALL</option>
          <option value="ANY">ANY</option>
        </select>
      </div>

      {/* Scope Identifier */}
      <div className="space-y-1">
        <label
          htmlFor={`${formHtmlId}-joinScopeId`}
          className="text-xs font-semibold text-slate-700 block"
        >
          Mã nhánh / phạm vi
        </label>
        <input
          id={`${formHtmlId}-joinScopeId`}
          type="text"
          data-testid="input-join-scope-id"
          disabled={readOnly}
          value={current.joinScopeId ?? ""}
          placeholder="Ví dụ: parallel_review_scope"
          onChange={(e) => update({ joinScopeId: e.target.value })}
          className="w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-xs disabled:bg-slate-100"
        />
      </div>

      {/* Remaining Branch Policy */}
      <div className="space-y-1">
        <label
          htmlFor={`${formHtmlId}-remainingBranchPolicy`}
          className="text-xs font-semibold text-slate-700 block"
        >
          Quy tắc nhánh còn lại
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
          <option value="CANCEL_REMAINING">CANCEL_REMAINING</option>
          <option value="AWAIT_COMPLETION">AWAIT_COMPLETION</option>
        </select>
      </div>
    </div>
  );
}
