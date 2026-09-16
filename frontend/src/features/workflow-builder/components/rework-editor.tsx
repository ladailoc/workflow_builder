"use client";

import { useId } from "react";
import type {
  MultiInstanceReworkScope,
  ReworkConfig,
  ReworkExhaustionBehavior,
  ReworkRollbackStrategy,
} from "../editor-types";
import type { BuilderNode } from "../types";

interface ReworkEditorProps {
  value?: ReworkConfig;
  onChange: (config: ReworkConfig) => void;
  availableNodes?: BuilderNode[];
  readOnly?: boolean;
}

export function ReworkEditor({
  value,
  onChange,
  availableNodes = [],
  readOnly = false,
}: ReworkEditorProps) {
  const formHtmlId = useId();

  const current: ReworkConfig = value ?? {
    targetStepId: availableNodes[0]?.id ?? "",
    maxIterations: 3,
    exhaustionBehavior: "FAIL_EVENT",
    rollbackStrategy: "KEEP_CURRENT",
    multiInstanceScope: "CURRENT_ITEM",
  };

  const update = (updates: Partial<ReworkConfig>) => {
    if (readOnly) return;
    onChange({ ...current, ...updates });
  };

  return (
    <div className="space-y-4" data-testid="rework-editor">
      <div className="border-b border-slate-200 pb-1.5">
        <h4 className="text-xs font-bold text-slate-800">
          Rework Loop Configuration
        </h4>
      </div>

      {/* Target Step */}
      <div className="space-y-1">
        <label
          htmlFor={`${formHtmlId}-targetStepId`}
          className="text-xs font-semibold text-slate-700 block"
        >
          Bước xử lý lại đích *
        </label>
        {availableNodes.length > 0 ? (
          <select
            id={`${formHtmlId}-targetStepId`}
            data-testid="select-rework-target-step"
            disabled={readOnly}
            value={current.targetStepId}
            onChange={(e) => update({ targetStepId: e.target.value })}
            className="w-full rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs text-slate-800 disabled:bg-slate-100 font-semibold"
          >
            {availableNodes.map((n) => (
              <option key={n.id} value={n.id}>
                {n.data.label} ({n.data.key})
              </option>
            ))}
          </select>
        ) : (
          <input
            id={`${formHtmlId}-targetStepId`}
            type="text"
            data-testid="input-rework-target-step"
            disabled={readOnly}
            value={current.targetStepId}
            placeholder="Mã bước cần quay lại"
            onChange={(e) => update({ targetStepId: e.target.value })}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-xs disabled:bg-slate-100"
          />
        )}
      </div>

      <div className="grid grid-cols-2 gap-3">
        {/* Max Rework Iterations */}
        <div>
          <label
            htmlFor={`${formHtmlId}-maxIterations`}
            className="text-[11px] font-semibold text-slate-700 block mb-0.5"
          >
            Số lần xử lý tối đa *
          </label>
          <input
            id={`${formHtmlId}-maxIterations`}
            type="number"
            min="1"
            max="20"
            data-testid="input-max-rework-iterations"
            disabled={readOnly}
            value={current.maxIterations}
            onChange={(e) =>
              update({ maxIterations: parseInt(e.target.value || "1", 10) })
            }
            className="w-full rounded border border-slate-300 px-2.5 py-1.5 text-xs disabled:bg-slate-100 font-semibold"
          />
        </div>

        {/* Exhaustion Behavior */}
        <div>
          <label
            htmlFor={`${formHtmlId}-exhaustionBehavior`}
            className="text-[11px] font-semibold text-slate-700 block mb-0.5"
          >
            Cách xử lý khi hết lượt
          </label>
          <select
            id={`${formHtmlId}-exhaustionBehavior`}
            data-testid="select-rework-exhaustion-behavior"
            disabled={readOnly}
            value={current.exhaustionBehavior}
            onChange={(e) =>
              update({
                exhaustionBehavior: e.target
                  .value as ReworkExhaustionBehavior,
              })
            }
            className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
          >
            <option value="FAIL_EVENT">FAIL_EVENT (Kết thúc với lỗi)</option>
            <option value="ROUTE_ESCALATION">
              ROUTE_ESCALATION (Chuyển cấp cho quản lý)
            </option>
          </select>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3">
        {/* Rollback Strategy */}
        <div>
          <label
            htmlFor={`${formHtmlId}-rollbackStrategy`}
            className="text-[11px] font-semibold text-slate-700 block mb-0.5"
          >
            Cách lưu dữ liệu trước khi xử lý lại
          </label>
          <select
            id={`${formHtmlId}-rollbackStrategy`}
            data-testid="select-rework-rollback-strategy"
            disabled={readOnly}
            value={current.rollbackStrategy}
            onChange={(e) =>
              update({
                rollbackStrategy: e.target
                  .value as ReworkRollbackStrategy,
              })
            }
            className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
          >
            <option value="KEEP_CURRENT">
              KEEP_CURRENT (Giữ thay đổi mới nhất)
            </option>
            <option value="RESTORE_ORIGINAL">
              RESTORE_ORIGINAL (Khôi phục giá trị trước xử lý lại)
            </option>
          </select>
        </div>

        {/* Multi-Instance Scope */}
        <div>
          <label
            htmlFor={`${formHtmlId}-multiInstanceScope`}
            className="text-[11px] font-semibold text-slate-700 block mb-0.5"
          >
            Phạm vi nhiều mục
          </label>
          <select
            id={`${formHtmlId}-multiInstanceScope`}
            data-testid="select-rework-mi-scope"
            disabled={readOnly}
            value={current.multiInstanceScope ?? "CURRENT_ITEM"}
            onChange={(e) =>
              update({
                multiInstanceScope: e.target
                  .value as MultiInstanceReworkScope,
              })
            }
            className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
          >
            <option value="CURRENT_ITEM">
              CURRENT_ITEM (Chỉ xử lý lại mục bị từ chối)
            </option>
            <option value="WHOLE_NODE">
              WHOLE_NODE (Xử lý lại toàn bộ danh sách)
            </option>
          </select>
        </div>
      </div>
    </div>
  );
}
