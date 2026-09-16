import type { ReactNode } from "react";

interface MetricCardProps {
  label: string;
  value: number | string;
  detail: string;
  icon: ReactNode;
  tone?: "blue" | "violet" | "emerald" | "amber";
}

const TONE_STYLES = {
  blue: "bg-blue-50 text-blue-700",
  violet: "bg-violet-50 text-violet-700",
  emerald: "bg-emerald-50 text-emerald-700",
  amber: "bg-amber-50 text-amber-700",
};

export function MetricCard({
  label,
  value,
  detail,
  icon,
  tone = "blue",
}: Readonly<MetricCardProps>) {
  return (
    <div className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-xs font-semibold text-slate-500">{label}</p>
          <p className="mt-3 text-3xl font-bold tracking-tight text-slate-950">{value}</p>
        </div>
        <div className={`flex h-10 w-10 items-center justify-center rounded-xl ${TONE_STYLES[tone]}`}>
          {icon}
        </div>
      </div>
      <p className="mt-3 text-xs text-slate-500">{detail}</p>
    </div>
  );
}

