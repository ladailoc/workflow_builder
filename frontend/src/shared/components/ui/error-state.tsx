interface ErrorStateProps {
  title?: string;
  message: string;
  code?: string;
  onRetry?: () => void;
}

export function ErrorState({
  title = "An error occurred",
  message,
  code,
  onRetry,
}: Readonly<ErrorStateProps>) {
  return (
    <div
      data-testid="error-state"
      className="rounded-xl border border-red-200 bg-red-50/50 p-6 shadow-xs"
    >
      <div className="flex items-start gap-4">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-red-100 text-red-600">
          <svg
            className="h-5 w-5"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
            />
          </svg>
        </div>
        <div className="flex-1">
          <div className="flex items-center gap-2">
            <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
            {code && (
              <span className="rounded bg-red-100 px-1.5 py-0.5 text-[10px] font-mono font-medium text-red-800">
                {code}
              </span>
            )}
          </div>
          <p className="mt-1 text-sm text-slate-600">{message}</p>
          {onRetry && (
            <div className="mt-4">
              <button
                type="button"
                onClick={onRetry}
                className="inline-flex items-center rounded-lg bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 shadow-xs border border-slate-300 hover:bg-slate-50 transition-colors"
              >
                Retry Request
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
