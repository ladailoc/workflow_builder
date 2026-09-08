interface LoadingStateProps {
  title?: string;
  description?: string;
  rows?: number;
}

export function LoadingState({
  title = "Loading data...",
  description = "Please wait while we fetch the latest records.",
  rows = 4,
}: Readonly<LoadingStateProps>) {
  return (
    <div
      data-testid="loading-state"
      className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs"
    >
      <div className="flex items-center gap-3">
        <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-600 border-t-transparent" />
        <div>
          <h3 className="text-sm font-semibold text-slate-800">{title}</h3>
          <p className="text-xs text-slate-500">{description}</p>
        </div>
      </div>
      <div className="mt-6 space-y-3">
        {Array.from({ length: rows }).map((_, i) => (
          <div
            key={i}
            className="h-9 w-full animate-pulse rounded-md bg-slate-100"
            style={{ opacity: 1 - i * 0.15 }}
          />
        ))}
      </div>
    </div>
  );
}
