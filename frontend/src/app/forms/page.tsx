"use client";

import { useMemo, useState } from "react";
import { apiPost, apiPut } from "@/shared/api/client";
import { AuthRouteGuard } from "@/features/auth";
import { PageHeader } from "@/shared/components/ui/page-header";
import { StatusBadge } from "@/shared/components/ui/status-badge";

type FieldType = "STRING" | "TEXTAREA" | "NUMBER" | "BOOLEAN" | "DATE" | "ENUM";
interface FormFieldDraft {
  fieldId: string;
  key: string;
  keyCustomized: boolean;
  label: string;
  description: string;
  type: FieldType;
  required: boolean;
}

function generatedKey(value: string, fallback: string) {
  const normalized = value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[đĐ]/g, "d")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
  const candidate = normalized || fallback;
  return /^[a-z]/.test(candidate) ? candidate : `${fallback}_${candidate}`;
}

function normalizedManualKey(value: string, fallback: string) {
  const candidate = value.toLowerCase().replace(/[^a-z0-9_.-]+/g, "_").replace(/^_+/, "");
  return /^[a-z]/.test(candidate) ? candidate : fallback;
}

const emptyField = (index: number): FormFieldDraft => ({
  fieldId: typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
  key: `field_${index}`,
  keyCustomized: false,
  label: "Trường mới",
  description: "",
  type: "STRING",
  required: false,
});

export default function FormsPage() {
  const [key, setKey] = useState(() => generatedKey("Biểu mẫu mới", "form"));
  const [keyCustomized, setKeyCustomized] = useState(false);
  const [name, setName] = useState("Biểu mẫu mới");
  const [description, setDescription] = useState("");
  const [fields, setFields] = useState<FormFieldDraft[]>([emptyField(1)]);
  const [formId, setFormId] = useState<string | null>(null);
  const [versionId, setVersionId] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const [status, setStatus] = useState<string>("DRAFT");
  const [showTechnical, setShowTechnical] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");

  const schema = useMemo(
    () => ({
      formKey: key,
      formType: "TICKET_FORM",
      fields: fields.map((field, index) => ({
        fieldId: field.fieldId,
        key: field.key,
        label: field.label,
        description: field.description || null,
        placeholder: null,
        order: index,
        type: { type: field.type },
        defaultValue: null,
        sensitive: false,
        requirement: { mode: field.required ? "ALWAYS" : "NEVER" },
        visibility: { mode: "ALWAYS" },
        editability: { mode: "EDITABLE" },
        validation: { minimum: null, maximum: null, minimumLength: null, maximumLength: null, regex: null, safeRules: [] },
        options: null,
        semantics: { participantCapable: false, businessSubject: false, filterable: false, reportable: false, searchable: false },
      })),
    }),
    [fields, key],
  );

  function updateField(index: number, next: Partial<FormFieldDraft>) {
    setFields((current) => current.map((field, i) => (i === index ? { ...field, ...next } : field)));
  }

  function updateFieldLabel(index: number, label: string) {
    setFields((current) => current.map((field, fieldIndex) => {
      if (fieldIndex !== index) return field;
      if (field.keyCustomized) return { ...field, label };
      const baseKey = generatedKey(label, `field_${index + 1}`);
      const usedKeys = new Set(current.filter((_, i) => i !== index).map((item) => item.key));
      let nextKey = baseKey;
      let suffix = 2;
      while (usedKeys.has(nextKey)) nextKey = `${baseKey}_${suffix++}`;
      return { ...field, label, key: nextKey };
    }));
  }

  function updateFieldKey(index: number, value: string) {
    updateField(index, { key: normalizedManualKey(value, `field_${index + 1}`), keyCustomized: true });
  }

  function updateName(value: string) {
    setName(value);
    if (!formId && !keyCustomized) setKey(generatedKey(value, "form"));
  }

  function updateStableKey(value: string) {
    setKey(normalizedManualKey(value, "form"));
    setKeyCustomized(true);
  }

  async function create() {
    setSaving(true);
    setMessage("");
    try {
      const created = await apiPost<{ form: { id: string }; draft: { id: string; revision: number } }>("/api/v1/forms", { key, name, description, schema });
      setFormId(created.form.id);
      setVersionId(created.draft.id);
      setRevision(created.draft.revision);
      setMessage("Đã tạo bản nháp.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Không thể tạo biểu mẫu");
    } finally {
      setSaving(false);
    }
  }

  async function save() {
    if (!formId || !versionId) {
      setMessage("Hãy tạo biểu mẫu trước khi lưu bản nháp.");
      return;
    }
    setSaving(true);
    try {
      const saved = await apiPut<{ revision?: number }>(`/api/v1/forms/${formId}/versions/${versionId}`, schema, { headers: { "If-Match": String(revision) } });
      setRevision(saved.revision ?? revision + 1);
      setMessage("Đã lưu bản nháp. Các nhu cầu đã phát hành vẫn sử dụng đúng phiên bản biểu mẫu đã được gắn.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Không thể lưu bản nháp biểu mẫu");
    } finally {
      setSaving(false);
    }
  }

  async function runLifecycle(action: "validate" | "publish") {
    if (!formId || !versionId) {
      setMessage("Hãy tạo biểu mẫu trước.");
      return;
    }
    setSaving(true);
    try {
      const result = await apiPost<{ valid?: boolean; status?: string }>(`/api/v1/forms/${formId}/versions/${versionId}/${action}`, undefined, action === "publish" ? { headers: { "If-Match": String(revision) } } : undefined);
      if (action === "publish") setStatus("PUBLISHED");
      setMessage(action === "validate" ? (result.valid === false ? "Kiểm tra phát hiện vấn đề. Hãy xem lại thông tin trước khi phát hành." : "Biểu mẫu hợp lệ để phát hành.") : "FormVersion đã được phát hành và không thể chỉnh sửa.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : `Không thể ${action === "validate" ? "kiểm tra" : "phát hành"} biểu mẫu`);
    } finally {
      setSaving(false);
    }
  }

  const editable = status === "DRAFT";

  return (
    <AuthRouteGuard roles={["WORKFLOW_OWNER", "WORKFLOW_EDITOR", "ADMIN"]}>
      <div className="space-y-7" data-testid="forms-page">
        <PageHeader eyebrow="Thiết kế hệ thống" title="Biểu mẫu" description="Tạo các biểu mẫu thu thập thông tin có thể dùng lại. Biểu mẫu chỉ lưu dữ liệu và không tự chọn hoặc khởi chạy quy trình." />
        <section className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm sm:p-6">
            <div className="flex items-start justify-between gap-4 border-b border-slate-100 pb-5">
              <div><h2 className="text-base font-semibold text-slate-950">Định nghĩa biểu mẫu</h2></div>
              <StatusBadge value={status} />
            </div>
            <div className="mt-5 space-y-4">
              <label className="block text-sm font-semibold text-slate-700">Tên biểu mẫu<input value={name} disabled={!editable} onChange={(event) => updateName(event.target.value)} className="mt-1.5 w-full rounded-xl border border-slate-300 px-3 py-2.5 text-sm font-normal disabled:bg-slate-100" /></label>
              <p className="text-xs text-slate-500">Khóa ổn định: <code className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[11px] text-slate-700">{key}</code></p>
              <label className="block text-sm font-semibold text-slate-700">Mô tả <span className="font-normal text-slate-400">(không bắt buộc)</span><textarea value={description} disabled={!editable} onChange={(event) => setDescription(event.target.value)} className="mt-1.5 min-h-20 w-full rounded-xl border border-slate-300 px-3 py-2.5 text-sm font-normal disabled:bg-slate-100" /></label>
              <div className="rounded-xl border border-slate-200 bg-slate-50/70">
                <button type="button" onClick={() => setShowTechnical((value) => !value)} className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left text-xs font-semibold text-slate-700">
                  <span>Tùy chọn kỹ thuật</span>
                  <span className="text-blue-700">{showTechnical ? "Ẩn" : "Hiện"}</span>
                </button>
                {showTechnical ? (
                  <div className="space-y-3 border-t border-slate-200 px-4 py-4">
                    <label className="block text-xs font-semibold text-slate-600">Khóa ổn định<input value={key} disabled={Boolean(formId) || !editable} onChange={(event) => updateStableKey(event.target.value)} className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-2.5 py-2 font-mono text-sm font-normal disabled:bg-slate-100" /></label>
                    {formId ? <p className="text-xs font-medium text-amber-700">Form đã tạo nên khóa ổn định không thể đổi.</p> : null}
                    <pre className="max-h-72 overflow-auto rounded-lg bg-slate-950 p-3 text-[11px] leading-5 text-slate-100">{JSON.stringify(schema, null, 2)}</pre>
                  </div>
                ) : null}
              </div>
            </div>

            <div className="mt-7 flex items-center justify-between gap-3"><h3 className="text-sm font-semibold text-slate-900">Các trường</h3><button type="button" disabled={!editable} onClick={() => setFields((current) => [...current, emptyField(current.length + 1)])} className="rounded-lg border border-slate-300 px-3 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50">+ Thêm trường</button></div>
            <div className="mt-4 space-y-3">
              {fields.map((field, index) => (
                <div key={field.fieldId} className="rounded-xl border border-slate-200 bg-slate-50/60 p-4">
                  <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_180px_auto]">
                    <label className="text-xs font-semibold text-slate-600">Nhãn<input value={field.label} disabled={!editable} onChange={(event) => updateFieldLabel(index, event.target.value)} className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-2.5 py-2 text-sm font-normal disabled:bg-slate-100" /></label>
                    <label className="text-xs font-semibold text-slate-600">Kiểu dữ liệu<select value={field.type} disabled={!editable} onChange={(event) => updateField(index, { type: event.target.value as FieldType })} className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-2.5 py-2 text-sm font-normal disabled:bg-slate-100">{["STRING", "TEXTAREA", "NUMBER", "BOOLEAN", "DATE", "ENUM"].map((type) => <option key={type}>{type}</option>)}</select></label>
                    <button type="button" aria-label={`Xóa ${field.label}`} disabled={!editable} onClick={() => setFields((current) => current.filter((_, i) => i !== index))} className="self-end rounded-lg px-2 py-2 text-slate-400 hover:bg-rose-50 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-40">×</button>
                  </div>
                  <div className="mt-3 flex flex-wrap items-center gap-3"><label className="flex items-center gap-2 text-xs font-medium text-slate-600"><input type="checkbox" checked={field.required} disabled={!editable} onChange={(event) => updateField(index, { required: event.target.checked })} /> Bắt buộc</label><span className="rounded-md bg-white px-2 py-1 font-mono text-[11px] text-slate-500">{field.key}</span></div>
                  {showTechnical ? <div className="mt-3 grid gap-3 border-t border-slate-200 pt-3 sm:grid-cols-2"><label className="text-xs font-semibold text-slate-600">Khóa trường<input value={field.key} disabled={!editable} onChange={(event) => updateFieldKey(index, event.target.value)} className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-2.5 py-2 font-mono text-sm font-normal disabled:bg-slate-100" /></label><label className="text-xs font-semibold text-slate-600">Gợi ý cho người dùng <span className="font-normal text-slate-400">(không bắt buộc)</span><input value={field.description} disabled={!editable} onChange={(event) => updateField(index, { description: event.target.value })} className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-2.5 py-2 text-xs font-normal disabled:bg-slate-100" /></label></div> : null}
                </div>
              ))}
            </div>

            <div className="mt-6 flex flex-wrap gap-2 border-t border-slate-100 pt-5"><button type="button" onClick={() => void create()} disabled={saving || Boolean(formId)} className="rounded-xl bg-slate-950 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50">{saving && !formId ? "Đang tạo…" : "Tạo bản nháp"}</button><button type="button" onClick={() => void save()} disabled={saving || !formId || !editable} className="rounded-xl border border-slate-300 px-4 py-2.5 text-sm font-semibold text-slate-700 disabled:opacity-50">Lưu bản nháp</button><button type="button" onClick={() => void runLifecycle("validate")} disabled={saving || !versionId || !editable} className="rounded-xl border border-blue-200 bg-blue-50 px-4 py-2.5 text-sm font-semibold text-blue-700 disabled:opacity-50">Kiểm tra</button><button type="button" onClick={() => void runLifecycle("publish")} disabled={saving || !versionId || !editable} className="rounded-xl bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-50">Phát hành phiên bản</button></div>
            {message ? <p role="status" className="mt-4 rounded-xl bg-slate-100 px-3 py-2.5 text-sm text-slate-700">{message}</p> : null}
        </section>
      </div>
    </AuthRouteGuard>
  );
}
