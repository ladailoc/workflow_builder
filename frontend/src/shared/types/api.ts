export type ApiError = Readonly<{
  code: string;
  message: string;
  field?: string;
  details?: unknown;
}>;
