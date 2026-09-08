"use client";

import { useId, useState } from "react";
import { findUnknownConfigProperties, getNodeManifest } from "../manifest";
import type { BuilderNode } from "../types";
import type { FormSchema } from "../form-types";
import { ParticipantBuilder } from "./participant-builder";
import { FormBuilder } from "./form-builder";

interface DynamicNodePropertiesProps {
  node: BuilderNode;
  onUpdateConfig: (config: Record<string, unknown>) => void;
  readOnly?: boolean;
}

export function DynamicNodeProperties({
  node,
  onUpdateConfig,
  readOnly = false,
}: DynamicNodePropertiesProps) {
  const manifest = getNodeManifest(node.data.nodeType);
  const config = node.data.config || {};
  const unknownProps = findUnknownConfigProperties(node.data.nodeType, config);

  const formHtmlId = useId();

  const handleConfigChange = (key: string, value: unknown) => {
    if (readOnly) return;
    const nextConfig = { ...config, [key]: value };
    if (value === undefined || value === "") {
      delete nextConfig[key];
    }
    onUpdateConfig(nextConfig);
  };

  const handlePruneUnknown = () => {
    if (readOnly) return;
    const nextConfig = { ...config };
    unknownProps.forEach((p) => delete nextConfig[p]);
    onUpdateConfig(nextConfig);
  };

  const schemaVersion = manifest?.configSchemaVersion ?? 1;

  return (
    <div className="space-y-4" data-testid="dynamic-node-properties">
      {/* Schema Version & Manifest Header */}
      <div className="flex items-center justify-between border-b border-slate-100 pb-3">
        <div className="flex items-center gap-2">
          <span
            data-testid="config-schema-version-badge"
            className="rounded bg-slate-100 px-2 py-0.5 font-mono text-[10px] font-bold text-slate-700"
          >
            Schema v{schemaVersion}
          </span>
          <span className="text-xs text-slate-500 capitalize">
            {manifest?.category} Manifest
          </span>
        </div>
      </div>

      {/* Strict Unknown Property Warning */}
      {unknownProps.length > 0 && (
        <div
          data-testid="unknown-properties-alert"
          className="rounded-lg border border-rose-300 bg-rose-50 p-3 text-xs text-rose-900 space-y-2"
        >
          <div className="flex items-center justify-between">
            <span className="font-bold">Strict Schema Violation</span>
            {!readOnly && (
              <button
                type="button"
                data-testid="prune-unknown-properties-btn"
                onClick={handlePruneUnknown}
                className="rounded bg-rose-600 px-2 py-0.5 text-[10px] font-semibold text-white hover:bg-rose-700"
              >
                Prune Undeclared
              </button>
            )}
          </div>
          <p className="text-[11px] text-rose-800">
            The following properties are not declared by the strict{" "}
            {node.data.nodeType} schema:
          </p>
          <ul className="list-disc pl-4 font-mono text-[11px]">
            {unknownProps.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </div>
      )}

      {/* Node-Specific Dynamic Form Sections */}
      {(node.data.nodeType === "APPROVAL" || node.data.nodeType === "REVIEW") && (
        <ApprovalPropertiesSection
          config={config}
          readOnly={readOnly}
          onChange={handleConfigChange}
          formHtmlId={formHtmlId}
        />
      )}

      {node.data.nodeType === "SYSTEM_ACTION" && (
        <SystemActionPropertiesSection
          config={config}
          readOnly={readOnly}
          onChange={handleConfigChange}
          formHtmlId={formHtmlId}
        />
      )}

      {node.data.nodeType === "JOIN" && (
        <JoinPropertiesSection
          config={config}
          readOnly={readOnly}
          onChange={handleConfigChange}
          formHtmlId={formHtmlId}
        />
      )}

      {node.data.nodeType === "SUB_WORKFLOW" && (
        <SubWorkflowPropertiesSection
          config={config}
          readOnly={readOnly}
          onChange={handleConfigChange}
          formHtmlId={formHtmlId}
        />
      )}

      {node.data.nodeType === "CONDITION" && (
        <ConditionPropertiesSection
          config={config}
          readOnly={readOnly}
          onChange={handleConfigChange}
          formHtmlId={formHtmlId}
        />
      )}

      {node.data.nodeType !== "APPROVAL" &&
        node.data.nodeType !== "REVIEW" &&
        node.data.nodeType !== "SYSTEM_ACTION" &&
        node.data.nodeType !== "JOIN" &&
        node.data.nodeType !== "SUB_WORKFLOW" &&
        node.data.nodeType !== "CONDITION" && (
          <GenericNodePropertiesSection
            config={config}
            readOnly={readOnly}
            onChange={handleConfigChange}
            formHtmlId={formHtmlId}
          />
        )}
    </div>
  );
}

/* =========================================================================
   Approval / Review Modular Properties Section
   ========================================================================= */
function ApprovalPropertiesSection({
  config,
  readOnly,
  onChange,
  formHtmlId,
}: {
  config: Record<string, unknown>;
  readOnly: boolean;
  onChange: (key: string, value: unknown) => void;
  formHtmlId: string;
}) {
  const [activeTab, setActiveTab] = useState<string>("general");
  const tabs = [
    { id: "general", label: "General" },
    { id: "assignee", label: "Assignee" },
    { id: "taskGen", label: "Task Gen" },
    { id: "form", label: "Form" },
    { id: "decision", label: "Decision" },
    { id: "sla", label: "SLA" },
    { id: "failure", label: "Failure" },
  ];

  return (
    <div className="space-y-3" data-testid="approval-properties-panel">
      {/* Sub-Tabs */}
      <div className="flex flex-wrap border-b border-slate-200 gap-1 pb-1">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            data-testid={`approval-tab-${t.id}`}
            onClick={() => setActiveTab(t.id)}
            className={`rounded px-2 py-1 text-[11px] font-semibold transition-colors ${
              activeTab === t.id
                ? "bg-blue-50 text-blue-700"
                : "text-slate-500 hover:text-slate-900"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {activeTab === "general" && (
        <div className="space-y-3 pt-1">
          <div>
            <label
              htmlFor={`${formHtmlId}-titleSnapshot`}
              className="text-xs font-semibold text-slate-700 block mb-1"
            >
              Task Title Snapshot
            </label>
            <input
              id={`${formHtmlId}-titleSnapshot`}
              type="text"
              data-testid="input-titleSnapshot"
              disabled={readOnly}
              value={String(config.titleSnapshot ?? "")}
              placeholder="e.g. Manager Review & Approval"
              onChange={(e) => onChange("titleSnapshot", e.target.value)}
              className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
            />
          </div>
          <div>
            <label
              htmlFor={`${formHtmlId}-priority`}
              className="text-xs font-semibold text-slate-700 block mb-1"
            >
              Priority (0 - 100)
            </label>
            <input
              id={`${formHtmlId}-priority`}
              type="number"
              min="0"
              max="100"
              data-testid="input-priority"
              disabled={readOnly}
              value={Number(config.priority ?? 50)}
              onChange={(e) =>
                onChange("priority", parseInt(e.target.value || "0", 10))
              }
              className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
            />
          </div>
        </div>
      )}

      {activeTab === "assignee" && (
        <div className="space-y-3 pt-1">
          <ParticipantBuilder
            value={
              typeof config.participant === "object" && config.participant !== null
                ? (config.participant as Record<string, unknown>)
                : {}
            }
            onChange={(compiled) => onChange("participant", compiled)}
            readOnly={readOnly}
          />
        </div>
      )}

      {activeTab === "taskGen" && (
        <div className="space-y-3 pt-1">
          <span className="text-xs font-semibold text-slate-700 block">
            Task Generation Mode
          </span>
          <label className="flex items-center gap-2 cursor-pointer text-xs text-slate-700">
            <input
              type="checkbox"
              data-testid="check-multi-instance"
              disabled={readOnly}
              checked={Boolean(config.multiInstance)}
              onChange={(e) => {
                if (e.target.checked) {
                  onChange("multiInstance", {
                    mode: "PARALLEL",
                    collectionExpression: "$.items",
                    itemVariable: "item",
                  });
                } else {
                  onChange("multiInstance", undefined);
                }
              }}
              className="h-4 w-4 rounded border-slate-300 text-blue-600"
            />
            <span>Enable Multi-Instance Task Generation</span>
          </label>
        </div>
      )}

      {activeTab === "form" && (
        <div className="space-y-4 pt-1">
          <div>
            <label
              htmlFor={`${formHtmlId}-formKey`}
              className="text-xs font-semibold text-slate-700 block mb-1"
            >
              Task Form Key
            </label>
            <input
              id={`${formHtmlId}-formKey`}
              type="text"
              data-testid="input-formKey"
              disabled={readOnly}
              value={String(config.formKey ?? "")}
              placeholder="e.g. expense_approval_form"
              onChange={(e) => onChange("formKey", e.target.value)}
              className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
            />
          </div>
          <div className="border-t border-slate-200 pt-3">
            <FormBuilder
              schema={
                (config.stepFormSchema as FormSchema) || { fields: [] }
              }
              onChange={(newSchema) => onChange("stepFormSchema", newSchema)}
              readOnly={readOnly}
              title="Step Form Schema"
              description="Configure form fields required for this approval decision."
            />
          </div>
        </div>
      )}

      {activeTab === "decision" && (
        <div className="space-y-3 pt-1">
          <span className="text-xs font-semibold text-slate-700 block mb-1">
            Allowed Decision Actions
          </span>
          <div className="space-y-1.5" data-testid="allowed-actions-group">
            {["APPROVED", "REJECTED", "REVISION_REQUESTED"].map((act) => {
              const currentArr: string[] = Array.isArray(config.allowedActions)
                ? (config.allowedActions as string[])
                : ["APPROVED", "REJECTED"];
              const checked = currentArr.includes(act);

              return (
                <label key={act} className="flex items-center gap-2 text-xs text-slate-700">
                  <input
                    type="checkbox"
                    disabled={readOnly}
                    checked={checked}
                    onChange={(e) => {
                      const next = e.target.checked
                        ? [...currentArr, act]
                        : currentArr.filter((x) => x !== act);
                      onChange("allowedActions", next);
                    }}
                    className="h-3.5 w-3.5 rounded border-slate-300 text-blue-600"
                  />
                  <span>{act}</span>
                </label>
              );
            })}
          </div>
        </div>
      )}

      {activeTab === "sla" && (
        <div className="space-y-3 pt-1">
          <label
            htmlFor={`${formHtmlId}-slaMinutes`}
            className="text-xs font-semibold text-slate-700 block mb-1"
          >
            SLA Duration (Minutes)
          </label>
          <input
            id={`${formHtmlId}-slaMinutes`}
            type="number"
            data-testid="input-sla-minutes"
            disabled={readOnly}
            value={
              typeof config.sla === "object" && config.sla !== null
                ? Number((config.sla as Record<string, unknown>).durationMinutes ?? 1440)
                : 1440
            }
            onChange={(e) => {
              const minutes = parseInt(e.target.value || "0", 10);
              onChange("sla", { durationMinutes: minutes });
            }}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          />
        </div>
      )}

      {activeTab === "failure" && (
        <div className="space-y-3 pt-1">
          <label
            htmlFor={`${formHtmlId}-failurePolicy`}
            className="text-xs font-semibold text-slate-700 block mb-1"
          >
            Failure Behavior
          </label>
          <select
            id={`${formHtmlId}-failurePolicy`}
            data-testid="select-failure-policy"
            disabled={readOnly}
            value={String(config.failurePolicy ?? "FAIL_EVENT")}
            onChange={(e) => onChange("failurePolicy", e.target.value)}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          >
            <option value="FAIL_EVENT">Terminate Event on Failure</option>
            <option value="ROUTE_ERROR">Route to Error Port</option>
          </select>
        </div>
      )}
    </div>
  );
}

/* =========================================================================
   System Action Modular Properties Section
   ========================================================================= */
function SystemActionPropertiesSection({
  config,
  readOnly,
  onChange,
  formHtmlId,
}: {
  config: Record<string, unknown>;
  readOnly: boolean;
  onChange: (key: string, value: unknown) => void;
  formHtmlId: string;
}) {
  const [activeTab, setActiveTab] = useState<string>("connector");
  const tabs = [
    { id: "connector", label: "Connector" },
    { id: "actionVersion", label: "Action Version" },
    { id: "retry", label: "Retry" },
    { id: "failure", label: "Failure" },
  ];

  return (
    <div className="space-y-3" data-testid="system-action-properties-panel">
      <div className="flex border-b border-slate-200 gap-1 pb-1">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            data-testid={`system-action-tab-${t.id}`}
            onClick={() => setActiveTab(t.id)}
            className={`rounded px-2 py-1 text-[11px] font-semibold transition-colors ${
              activeTab === t.id
                ? "bg-violet-50 text-violet-700"
                : "text-slate-500 hover:text-slate-900"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {activeTab === "connector" && (
        <div className="space-y-3 pt-1">
          <div>
            <label
              htmlFor={`${formHtmlId}-connectorKey`}
              className="text-xs font-semibold text-slate-700 block mb-1"
            >
              Connector Key *
            </label>
            <input
              id={`${formHtmlId}-connectorKey`}
              type="text"
              data-testid="input-connectorKey"
              disabled={readOnly}
              value={String(config.connectorKey ?? "")}
              placeholder="e.g. slack, payment_gateway, jira"
              onChange={(e) => onChange("connectorKey", e.target.value)}
              className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
            />
          </div>
          <div>
            <label
              htmlFor={`${formHtmlId}-credentialRef`}
              className="text-xs font-semibold text-slate-700 block mb-1"
            >
              Credential Reference
            </label>
            <input
              id={`${formHtmlId}-credentialRef`}
              type="text"
              data-testid="input-credentialRef"
              disabled={readOnly}
              value={String(config.credentialRef ?? "")}
              placeholder="e.g. vault:secret/slack-token"
              onChange={(e) => onChange("credentialRef", e.target.value)}
              className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100 font-mono text-[11px]"
            />
          </div>
        </div>
      )}

      {activeTab === "actionVersion" && (
        <div className="space-y-3 pt-1">
          <div>
            <label
              htmlFor={`${formHtmlId}-actionKey`}
              className="text-xs font-semibold text-slate-700 block mb-1"
            >
              Action Key *
            </label>
            <input
              id={`${formHtmlId}-actionKey`}
              type="text"
              data-testid="input-actionKey"
              disabled={readOnly}
              value={String(config.actionKey ?? "")}
              placeholder="e.g. post_message, charge_card"
              onChange={(e) => onChange("actionKey", e.target.value)}
              className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
            />
          </div>
          <div>
            <label
              htmlFor={`${formHtmlId}-actionVersion`}
              className="text-xs font-semibold text-slate-700 block mb-1"
            >
              Action Version *
            </label>
            <input
              id={`${formHtmlId}-actionVersion`}
              type="number"
              min="1"
              data-testid="input-actionVersion"
              disabled={readOnly}
              value={Number(config.actionVersion ?? 1)}
              onChange={(e) =>
                onChange("actionVersion", parseInt(e.target.value || "1", 10))
              }
              className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
            />
          </div>
        </div>
      )}

      {activeTab === "retry" && (
        <div className="space-y-3 pt-1">
          <label
            htmlFor={`${formHtmlId}-maxAttempts`}
            className="text-xs font-semibold text-slate-700 block mb-1"
          >
            Max Retry Attempts
          </label>
          <input
            id={`${formHtmlId}-maxAttempts`}
            type="number"
            min="0"
            data-testid="input-max-attempts"
            disabled={readOnly}
            value={
              typeof config.retryPolicy === "object" && config.retryPolicy !== null
                ? Number((config.retryPolicy as Record<string, unknown>).maxAttempts ?? 3)
                : 3
            }
            onChange={(e) => {
              const attempts = parseInt(e.target.value || "0", 10);
              onChange("retryPolicy", { maxAttempts: attempts });
            }}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          />
        </div>
      )}

      {activeTab === "failure" && (
        <div className="space-y-3 pt-1">
          <label
            htmlFor={`${formHtmlId}-failureAction`}
            className="text-xs font-semibold text-slate-700 block mb-1"
          >
            Failure Route Action
          </label>
          <select
            id={`${formHtmlId}-failureAction`}
            data-testid="select-failure-action"
            disabled={readOnly}
            value={String(config.failureAction ?? "ROUTE_ERROR_PORT")}
            onChange={(e) => onChange("failureAction", e.target.value)}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          >
            <option value="ROUTE_ERROR_PORT">Route to ERROR port</option>
            <option value="FAIL_EVENT">Fail Event Immediately</option>
          </select>
        </div>
      )}
    </div>
  );
}

/* =========================================================================
   Join Modular Properties Section (Strict ALL/ANY, no unsupported N_OF_M)
   ========================================================================= */
function JoinPropertiesSection({
  config,
  readOnly,
  onChange,
  formHtmlId,
}: {
  config: Record<string, unknown>;
  readOnly: boolean;
  onChange: (key: string, value: unknown) => void;
  formHtmlId: string;
}) {
  const [activeTab, setActiveTab] = useState<string>("policy");
  const tabs = [
    { id: "policy", label: "Join Policy" },
    { id: "scope", label: "Scope" },
    { id: "remaining", label: "Remaining Branch" },
  ];

  return (
    <div className="space-y-3" data-testid="join-properties-panel">
      <div className="flex border-b border-slate-200 gap-1 pb-1">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            data-testid={`join-tab-${t.id}`}
            onClick={() => setActiveTab(t.id)}
            className={`rounded px-2 py-1 text-[11px] font-semibold transition-colors ${
              activeTab === t.id
                ? "bg-indigo-50 text-indigo-700"
                : "text-slate-500 hover:text-slate-900"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {activeTab === "policy" && (
        <div className="space-y-3 pt-1">
          <label
            htmlFor={`${formHtmlId}-joinPolicy`}
            className="text-xs font-semibold text-slate-700 block mb-1"
          >
            Join Execution Policy
          </label>
          <select
            id={`${formHtmlId}-joinPolicy`}
            data-testid="select-join-policy"
            disabled={readOnly}
            value={String(config.policy ?? "ALL")}
            onChange={(e) => onChange("policy", e.target.value)}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          >
            <option value="ALL">ALL (Wait for all inbound branches)</option>
            <option value="ANY">ANY (First arriving branch triggers)</option>
          </select>
        </div>
      )}

      {activeTab === "scope" && (
        <div className="space-y-3 pt-1">
          <label
            htmlFor={`${formHtmlId}-joinScopeId`}
            className="text-xs font-semibold text-slate-700 block mb-1"
          >
            Join Scope Identifier
          </label>
          <input
            id={`${formHtmlId}-joinScopeId`}
            type="text"
            data-testid="input-joinScopeId"
            disabled={readOnly}
            value={String(config.joinScopeId ?? "")}
            placeholder="Scope UUID or branch reference"
            onChange={(e) => onChange("joinScopeId", e.target.value)}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100 font-mono text-[11px]"
          />
        </div>
      )}

      {activeTab === "remaining" && (
        <div className="space-y-3 pt-1">
          <label
            htmlFor={`${formHtmlId}-remainingBranchPolicy`}
            className="text-xs font-semibold text-slate-700 block mb-1"
          >
            Remaining Branch Policy
          </label>
          <select
            id={`${formHtmlId}-remainingBranchPolicy`}
            data-testid="select-remaining-branch-policy"
            disabled={readOnly}
            value={String(config.remainingBranchPolicy ?? "CANCEL_REMAINING")}
            onChange={(e) => onChange("remainingBranchPolicy", e.target.value)}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          >
            <option value="CANCEL_REMAINING">Cancel Remaining Branches</option>
            <option value="AWAIT_COMPLETION">Await All Branches Silently</option>
          </select>
        </div>
      )}
    </div>
  );
}

/* =========================================================================
   SubWorkflow Modular Properties Section
   ========================================================================= */
function SubWorkflowPropertiesSection({
  config,
  readOnly,
  onChange,
  formHtmlId,
}: {
  config: Record<string, unknown>;
  readOnly: boolean;
  onChange: (key: string, value: unknown) => void;
  formHtmlId: string;
}) {
  const [activeTab, setActiveTab] = useState<string>("childDef");
  const tabs = [
    { id: "childDef", label: "Definition" },
    { id: "mode", label: "Execution Mode" },
    { id: "mapping", label: "Mappings" },
    { id: "cancellation", label: "Cancellation" },
  ];

  return (
    <div className="space-y-3" data-testid="subworkflow-properties-panel">
      <div className="flex border-b border-slate-200 gap-1 pb-1">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            data-testid={`subwf-tab-${t.id}`}
            onClick={() => setActiveTab(t.id)}
            className={`rounded px-2 py-1 text-[11px] font-semibold transition-colors ${
              activeTab === t.id
                ? "bg-purple-50 text-purple-700"
                : "text-slate-500 hover:text-slate-900"
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {activeTab === "childDef" && (
        <div className="space-y-3 pt-1">
          <label
            htmlFor={`${formHtmlId}-childDefKey`}
            className="text-xs font-semibold text-slate-700 block mb-1"
          >
            Child Workflow Definition Key *
          </label>
          <input
            id={`${formHtmlId}-childDefKey`}
            type="text"
            data-testid="input-childWorkflowDefinitionKey"
            disabled={readOnly}
            value={String(config.childWorkflowDefinitionKey ?? "")}
            placeholder="e.g. employee_onboarding"
            onChange={(e) => onChange("childWorkflowDefinitionKey", e.target.value)}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100 font-mono"
          />
        </div>
      )}

      {activeTab === "mode" && (
        <div className="space-y-3 pt-1">
          <label
            htmlFor={`${formHtmlId}-execMode`}
            className="text-xs font-semibold text-slate-700 block mb-1"
          >
            Execution Mode
          </label>
          <select
            id={`${formHtmlId}-execMode`}
            data-testid="select-executionMode"
            disabled={readOnly}
            value={String(config.executionMode ?? "WAIT_FOR_COMPLETION")}
            onChange={(e) => onChange("executionMode", e.target.value)}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          >
            <option value="WAIT_FOR_COMPLETION">WAIT_FOR_COMPLETION</option>
            <option value="FIRE_AND_CONTINUE">FIRE_AND_CONTINUE</option>
          </select>
        </div>
      )}

      {activeTab === "mapping" && (
        <div className="space-y-3 pt-1">
          <span className="text-xs font-semibold text-slate-700 block">
            Parent ↔ Child Variable Mappings
          </span>
          <p className="text-[11px] text-slate-400">
            Configure input/output parameters explicitly.
          </p>
        </div>
      )}

      {activeTab === "cancellation" && (
        <div className="space-y-3 pt-1">
          <label
            htmlFor={`${formHtmlId}-cancelPolicy`}
            className="text-xs font-semibold text-slate-700 block mb-1"
          >
            Cancellation Propagation
          </label>
          <select
            id={`${formHtmlId}-cancelPolicy`}
            data-testid="select-cancellationPolicy"
            disabled={readOnly}
            value={String(config.cancellationPolicy ?? "PROPAGATE")}
            onChange={(e) => onChange("cancellationPolicy", e.target.value)}
            className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
          >
            <option value="PROPAGATE">PROPAGATE (Cancel Child on Parent Terminate)</option>
            <option value="DETACH">DETACH (Allow Child to Complete Independently)</option>
          </select>
        </div>
      )}
    </div>
  );
}

/* =========================================================================
   Condition Properties Section
   ========================================================================= */
function ConditionPropertiesSection({
  config,
  readOnly,
  onChange,
  formHtmlId,
}: {
  config: Record<string, unknown>;
  readOnly: boolean;
  onChange: (key: string, value: unknown) => void;
  formHtmlId: string;
}) {
  return (
    <div className="space-y-3 pt-1" data-testid="condition-properties-panel">
      <label
        htmlFor={`${formHtmlId}-conditionExpr`}
        className="text-xs font-semibold text-slate-700 block mb-1"
      >
        Condition Expression (JSON AST)
      </label>
      <textarea
        id={`${formHtmlId}-conditionExpr`}
        rows={4}
        data-testid="input-condition-expression"
        disabled={readOnly}
        value={
          typeof config.expression === "object" && config.expression !== null
            ? JSON.stringify(config.expression, null, 2)
            : String(config.expression ?? "")
        }
        placeholder='{ "kind": "OPERATOR", "operator": "GT", "operands": [...] }'
        onChange={(e) => {
          try {
            const parsed = JSON.parse(e.target.value);
            onChange("expression", parsed);
          } catch {
            onChange("expression", e.target.value);
          }
        }}
        className="w-full rounded-lg border border-slate-300 p-2 font-mono text-[11px] disabled:bg-slate-100"
      />
    </div>
  );
}

/* =========================================================================
   Generic Node Properties Section
   ========================================================================= */
function GenericNodePropertiesSection({
  config,
  readOnly,
  onChange,
  formHtmlId,
}: {
  config: Record<string, unknown>;
  readOnly: boolean;
  onChange: (key: string, value: unknown) => void;
  formHtmlId: string;
}) {
  return (
    <div className="space-y-3 pt-1">
      <label
        htmlFor={`${formHtmlId}-outcome`}
        className="text-xs font-semibold text-slate-700 block mb-1"
      >
        Outcome Value
      </label>
      <input
        id={`${formHtmlId}-outcome`}
        type="text"
        disabled={readOnly}
        value={String(config.outcome ?? "")}
        placeholder="COMPLETED"
        onChange={(e) => onChange("outcome", e.target.value)}
        className="w-full rounded-lg border border-slate-300 px-3 py-1.5 text-xs disabled:bg-slate-100"
      />
    </div>
  );
}
