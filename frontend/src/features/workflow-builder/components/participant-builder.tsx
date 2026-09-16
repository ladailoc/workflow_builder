"use client";

import { useId, useState } from "react";
import type {
  CompiledParticipantConfig,
  FriendlyParticipantConfig,
  FriendlyResolverKind,
  TaskGenerationMode,
  CompletionPolicy,
} from "../participant-types";
import {
  compileParticipantConfig,
  decompileParticipantConfig,
} from "../utils/participant-compiler";

const RESOLVER_OPTIONS: { value: FriendlyResolverKind; label: string }[] = [
  {
    value: "CREATORS_MANAGER",
    label: "Quản lý trực tiếp của người tạo",
  },
  {
    value: "MANAGER_N_LEVELS_UP",
    label: "Quản lý cách N cấp",
  },
  {
    value: "DEPARTMENT_HEAD",
    label: "Trưởng phòng ban",
  },
  {
    value: "CREATOR",
    label: "Người tạo / gửi yêu cầu",
  },
  {
    value: "FIXED_USER",
    label: "Người dùng cố định",
  },
  {
    value: "ROLE",
    label: "Vai trò tổ chức",
  },
  {
    value: "GROUP",
    label: "Nhóm tổ chức",
  },
  {
    value: "REQUEST_FIELD",
    label: "Trường biểu mẫu yêu cầu",
  },
  {
    value: "ITEM_MANAGER",
    label: "Quản lý tài sản / mục",
  },
  {
    value: "ITEM_USER",
    label: "Người dùng / người thụ hưởng",
  },
  {
    value: "PREVIOUS_PARTICIPANT",
    label: "Người xử lý bước trước",
  },
  {
    value: "NODE_OUTPUT",
    label: "Kết quả thao tác hệ thống",
  },
  {
    value: "EXPRESSION",
    label: "Biểu thức tùy chỉnh",
  },
];

interface ParticipantBuilderProps {
  value?: Record<string, unknown>;
  onChange: (compiled: CompiledParticipantConfig) => void;
  readOnly?: boolean;
}

export function ParticipantBuilder({
  value,
  onChange,
  readOnly = false,
}: ParticipantBuilderProps) {
  // Decompile incoming raw/compiled config to friendly state
  const [friendly, setFriendly] = useState<FriendlyParticipantConfig>(() =>
    value && Object.keys(value).length > 0
      ? decompileParticipantConfig(value)
      : {
          kind: "CREATORS_MANAGER",
          depth: 1,
          cardinality: "SINGLE",
          taskGenerationMode: "ONE_PER_PARTICIPANT",
          completionPolicy: "FIRST_RESPONSE",
          fallbackChain: [],
        },
  );

  const formHtmlId = useId();

  // Commit changes to parent
  const updateFriendly = (updates: Partial<FriendlyParticipantConfig>) => {
    if (readOnly) return;
    const next = { ...friendly, ...updates };
    setFriendly(next);
    onChange(compileParticipantConfig(next));
  };

  // Fallback management
  const handleAddFallback = () => {
    if (readOnly) return;
    const chain = friendly.fallbackChain ? [...friendly.fallbackChain] : [];
    chain.push({ kind: "DEPARTMENT_HEAD" });
    updateFriendly({ fallbackChain: chain });
  };

  const handleUpdateFallback = (
    index: number,
    updates: Partial<FriendlyParticipantConfig>,
  ) => {
    if (readOnly) return;
    const chain = friendly.fallbackChain ? [...friendly.fallbackChain] : [];
    chain[index] = { ...chain[index], ...updates };
    updateFriendly({ fallbackChain: chain });
  };

  const handleRemoveFallback = (index: number) => {
    if (readOnly) return;
    const chain = friendly.fallbackChain ? [...friendly.fallbackChain] : [];
    chain.splice(index, 1);
    updateFriendly({ fallbackChain: chain });
  };

  const isMulti = friendly.cardinality === "MULTI";
  return (
    <div className="space-y-4" data-testid="participant-builder">
      {/* 1. Primary Resolver Selection */}
      <div className="space-y-2">
        <label
          htmlFor={`${formHtmlId}-primaryResolver`}
          className="text-xs font-semibold text-slate-800 block"
        >
          Cách xác định người xử lý chính *
        </label>
        <select
          id={`${formHtmlId}-primaryResolver`}
          data-testid="select-primary-resolver"
          disabled={readOnly}
          value={friendly.kind}
          onChange={(e) =>
            updateFriendly({ kind: e.target.value as FriendlyResolverKind })
          }
          className="w-full rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs text-slate-800 disabled:bg-slate-100"
        >
          {RESOLVER_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      </div>

      {/* 2. Specific Resolver Options */}
      {friendly.kind === "FIXED_USER" && (
        <div className="space-y-1">
          <label
            htmlFor={`${formHtmlId}-userId`}
            className="text-xs font-semibold text-slate-700 block"
          >
            UUID người dùng *
          </label>
          <input
            id={`${formHtmlId}-userId`}
            type="text"
            data-testid="input-fixed-user-id"
            disabled={readOnly}
            value={friendly.userId ?? ""}
            placeholder="Ví dụ: 11111111-1111-1111-1111-111111111111"
            onChange={(e) => updateFriendly({ userId: e.target.value })}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-xs disabled:bg-slate-100"
          />
        </div>
      )}

      {friendly.kind === "MANAGER_N_LEVELS_UP" && (
        <div className="space-y-1">
          <label
            htmlFor={`${formHtmlId}-depth`}
            className="text-xs font-semibold text-slate-700 block"
          >
            Số cấp quản lý *
          </label>
          <input
            id={`${formHtmlId}-depth`}
            type="number"
            min="2"
            max="10"
            data-testid="input-hierarchy-depth"
            disabled={readOnly}
            value={friendly.depth ?? 2}
            onChange={(e) =>
              updateFriendly({ depth: parseInt(e.target.value || "2", 10) })
            }
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          />
        </div>
      )}

      {friendly.kind === "ITEM_MANAGER" && (
        <div className="space-y-1">
          <label
            htmlFor={`${formHtmlId}-itemDepth`}
            className="text-xs font-semibold text-slate-700 block"
          >
            Số cấp quản lý tài sản *
          </label>
          <input
            id={`${formHtmlId}-itemDepth`}
            type="number"
            min="1"
            max="10"
            data-testid="input-item-depth"
            disabled={readOnly}
            value={friendly.depth ?? 1}
            onChange={(e) =>
              updateFriendly({ depth: parseInt(e.target.value || "1", 10) })
            }
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          />
        </div>
      )}

      {friendly.kind === "REQUEST_FIELD" && (
        <div className="space-y-1">
          <label
            htmlFor={`${formHtmlId}-fieldKey`}
            className="text-xs font-semibold text-slate-700 block"
          >
            Khóa trường biểu mẫu *
          </label>
          <input
            id={`${formHtmlId}-fieldKey`}
            type="text"
            data-testid="input-request-field-key"
            disabled={readOnly}
            value={friendly.fieldKey ?? ""}
            placeholder="Ví dụ: designatedApproverId"
            onChange={(e) => updateFriendly({ fieldKey: e.target.value })}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-xs disabled:bg-slate-100"
          />
        </div>
      )}

      {friendly.kind === "ROLE" && (
        <div className="space-y-1">
          <label
            htmlFor={`${formHtmlId}-role`}
            className="text-xs font-semibold text-slate-700 block"
          >
            Tên / mã vai trò *
          </label>
          <input
            id={`${formHtmlId}-role`}
            type="text"
            data-testid="input-role-name"
            disabled={readOnly}
            value={friendly.role ?? ""}
            placeholder="Ví dụ: FINANCE_DIRECTOR, LEGAL_REVIEWER"
            onChange={(e) => updateFriendly({ role: e.target.value })}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          />
        </div>
      )}

      {friendly.kind === "GROUP" && (
        <div className="space-y-1">
          <label
            htmlFor={`${formHtmlId}-group`}
            className="text-xs font-semibold text-slate-700 block"
          >
            Mã nhóm *
          </label>
          <input
            id={`${formHtmlId}-group`}
            type="text"
            data-testid="input-group-code"
            disabled={readOnly}
            value={friendly.group ?? ""}
            placeholder="Ví dụ: IT_DESK, SECURITY_TEAM"
            onChange={(e) => updateFriendly({ group: e.target.value })}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          />
        </div>
      )}

      {friendly.kind === "PREVIOUS_PARTICIPANT" && (
        <div className="space-y-1">
          <label
            htmlFor={`${formHtmlId}-stepId`}
            className="text-xs font-semibold text-slate-700 block"
          >
            Mã bước trước *
          </label>
          <input
            id={`${formHtmlId}-stepId`}
            type="text"
            data-testid="input-step-id"
            disabled={readOnly}
            value={friendly.stepId ?? ""}
            placeholder="Ví dụ: initial_review"
            onChange={(e) => updateFriendly({ stepId: e.target.value })}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-xs disabled:bg-slate-100"
          />
        </div>
      )}

      {friendly.kind === "EXPRESSION" && (
        <div className="space-y-1">
          <label
            htmlFor={`${formHtmlId}-expression`}
            className="text-xs font-semibold text-slate-700 block"
          >
            Biểu thức quy tắc *
          </label>
          <textarea
            id={`${formHtmlId}-expression`}
            rows={2}
            data-testid="input-rule-expression"
            disabled={readOnly}
            value={friendly.expression ?? ""}
            placeholder="payload.amount > 5000 ? 'cfo' : 'finance_manager'"
            onChange={(e) => updateFriendly({ expression: e.target.value })}
            className="w-full rounded-lg border border-slate-300 p-2 font-mono text-xs disabled:bg-slate-100"
          />
        </div>
      )}

      {/* 3. Cardinality & Multi-Participant Configuration */}
      <div className="rounded-lg border border-slate-200 bg-slate-50/70 p-3 space-y-3">
        <div className="flex items-center justify-between">
          <span className="text-xs font-bold text-slate-800">
            Số lượng người xử lý
          </span>
          <div className="flex items-center gap-1 rounded bg-slate-200 p-0.5">
            <button
              type="button"
              data-testid="cardinality-single-btn"
              disabled={readOnly}
              onClick={() =>
                updateFriendly({
                  cardinality: "SINGLE",
                  taskGenerationMode: "ONE_PER_PARTICIPANT",
                  completionPolicy: "FIRST_RESPONSE",
                })
              }
              className={`rounded px-2.5 py-1 text-[11px] font-semibold transition-colors ${
                !isMulti
                  ? "bg-white text-blue-700 shadow-2xs"
                  : "text-slate-600 hover:text-slate-900"
              }`}
            >
              Một người
            </button>
            <button
              type="button"
              data-testid="cardinality-multi-btn"
              disabled={readOnly}
              onClick={() =>
                updateFriendly({
                  cardinality: "MULTI",
                  taskGenerationMode: "ONE_PER_PARTICIPANT",
                  completionPolicy: "ALL_MUST_APPROVE",
                })
              }
              className={`rounded px-2.5 py-1 text-[11px] font-semibold transition-colors ${
                isMulti
                  ? "bg-white text-blue-700 shadow-2xs"
                  : "text-slate-600 hover:text-slate-900"
              }`}
            >
              Nhiều người
            </button>
          </div>
        </div>

        {isMulti && (
          <div className="space-y-3 pt-2 border-t border-slate-200">
            {/* Task Generation Mode */}
            <div>
              <label
                htmlFor={`${formHtmlId}-taskGenMode`}
                className="text-[11px] font-semibold text-slate-700 block mb-1"
              >
                Cách tạo công việc
              </label>
              <select
                id={`${formHtmlId}-taskGenMode`}
                data-testid="select-task-generation-mode"
                disabled={readOnly}
                value={friendly.taskGenerationMode ?? "ONE_PER_PARTICIPANT"}
                onChange={(e) =>
                  updateFriendly({
                    taskGenerationMode: e.target.value as TaskGenerationMode,
                  })
                }
                className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
              >
                <option value="ONE_PER_PARTICIPANT">
                  ONE_PER_PARTICIPANT (Mỗi người một công việc)
                </option>
                <option value="SINGLE_CLAIMABLE">
                  SINGLE_CLAIMABLE (Công việc chung, thành viên bất kỳ có thể nhận)
                </option>
              </select>
            </div>

            {/* Completion Policy */}
            <div>
              <label
                htmlFor={`${formHtmlId}-completionPolicy`}
                className="text-[11px] font-semibold text-slate-700 block mb-1"
              >
                Quy tắc hoàn tất khi có nhiều người xử lý
              </label>
              <select
                id={`${formHtmlId}-completionPolicy`}
                data-testid="select-completion-policy"
                disabled={readOnly}
                value={friendly.completionPolicy ?? "ALL_MUST_APPROVE"}
                onChange={(e) =>
                  updateFriendly({
                    completionPolicy: e.target.value as CompletionPolicy,
                  })
                }
                className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
              >
                <option value="ALL_MUST_APPROVE">
                  ALL_MUST_APPROVE (Cần 100% đồng thuận)
                </option>
                <option value="FIRST_RESPONSE">
                  FIRST_RESPONSE (Quyết định đầu tiên hoàn tất bước)
                </option>
                <option value="PERCENTAGE">
                  PERCENTAGE (Theo tỷ lệ phê duyệt)
                </option>
                <option value="QUORUM">
                  QUORUM (Theo số lượng phê duyệt tối thiểu)
                </option>
              </select>
            </div>

            {friendly.completionPolicy === "PERCENTAGE" && (
              <div>
                <label
                  htmlFor={`${formHtmlId}-completionPercent`}
                  className="text-[11px] font-semibold text-slate-700 block mb-1"
                >
                  Tỷ lệ phê duyệt bắt buộc (%)
                </label>
                <input
                  id={`${formHtmlId}-completionPercent`}
                  type="number"
                  min="1"
                  max="100"
                  data-testid="input-completion-percentage"
                  disabled={readOnly}
                  value={friendly.completionPercentage ?? 50}
                  onChange={(e) =>
                    updateFriendly({
                      completionPercentage: parseInt(e.target.value || "50", 10),
                    })
                  }
                  className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
                />
              </div>
            )}

            {friendly.completionPolicy === "QUORUM" && (
              <div>
                <label
                  htmlFor={`${formHtmlId}-quorumCount`}
                  className="text-[11px] font-semibold text-slate-700 block mb-1"
                >
                  Số phiếu tối thiểu
                </label>
                <input
                  id={`${formHtmlId}-quorumCount`}
                  type="number"
                  min="1"
                  max="50"
                  data-testid="input-quorum-count"
                  disabled={readOnly}
                  value={friendly.quorumCount ?? 2}
                  onChange={(e) =>
                    updateFriendly({
                      quorumCount: parseInt(e.target.value || "2", 10),
                    })
                  }
                  className="w-full rounded border border-slate-300 bg-white px-2.5 py-1.5 text-xs disabled:bg-slate-100"
                />
              </div>
            )}
          </div>
        )}
      </div>

      {/* 4. Fallback Resolution Chain */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <div>
            <span className="text-xs font-bold text-slate-800 block">
              Chuỗi người xử lý dự phòng
            </span>
          </div>
          {!readOnly && (
            <button
              type="button"
              data-testid="add-fallback-btn"
              onClick={handleAddFallback}
              className="rounded bg-slate-100 px-2 py-1 text-[11px] font-semibold text-slate-700 hover:bg-slate-200"
            >
              + Thêm dự phòng
            </button>
          )}
        </div>

        {friendly.fallbackChain && friendly.fallbackChain.length > 0 ? (
          <div className="space-y-2">
            {friendly.fallbackChain.map((fb, idx) => (
              <div
                key={idx}
                data-testid={`fallback-item-${idx}`}
                className="flex items-center justify-between rounded-lg border border-slate-200 bg-white p-2.5 shadow-2xs"
              >
                <div className="flex items-center gap-2">
                  <span className="flex h-5 w-5 items-center justify-center rounded-full bg-slate-100 font-mono text-[10px] font-bold text-slate-600">
                    {idx + 1}
                  </span>
                  <select
                    data-testid={`select-fallback-resolver-${idx}`}
                    disabled={readOnly}
                    value={fb.kind}
                    onChange={(e) =>
                      handleUpdateFallback(idx, {
                        kind: e.target.value as FriendlyResolverKind,
                      })
                    }
                    className="rounded border border-slate-300 bg-white px-2 py-1 text-xs"
                  >
                    {RESOLVER_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </div>
                {!readOnly && (
                  <button
                    type="button"
                    data-testid={`remove-fallback-btn-${idx}`}
                    onClick={() => handleRemoveFallback(idx)}
                    className="text-xs text-rose-600 hover:text-rose-800"
                  >
                    Xóa
                  </button>
                )}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-[11px] italic text-slate-400">Chưa cấu hình người xử lý dự phòng.</p>
        )}
      </div>
    </div>
  );
}
