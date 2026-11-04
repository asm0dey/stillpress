export type Role = 'administrator' | 'editor' | 'author' | 'contributor';

/** The body of a non-2xx response, plus its status. */
export interface ApiError {
  status: number;
  error?: string;
  field?: string;
  message?: string;
}

/** GET /session */
export interface SessionUser { id: number; login: string; displayName: string; role: Role }
/** GET /site */
export interface Site { title: string; homeUrl: string; language: string; items: number; comments: number }
/** GET /users */
export interface UserView {
  id: number; login: string; displayName: string; email: string; role: Role; hasPassword: boolean;
}
/** GET and POST /import */
export interface ImportStatus {
  state: 'idle' | 'running' | 'done' | 'failed';
  items: number;
  media: number;
  report: string | null;
  error: string | null;
  contentLeft: boolean;
}

/**
 * Calls the admin JSON API. Every request carries the CSRF header; a FormData body goes as multipart (the browser
 * sets its Content-Type), anything else as JSON. Non-2xx throws an ApiError; T is the JSON response type (void for 204).
 */
export async function api<T = void>(
  path: string,
  { method = 'GET', body }: { method?: string; body?: unknown } = {},
): Promise<T> {
  const headers: Record<string, string> = { 'X-Requested-With': 'stillpress' };
  const form = body instanceof FormData;
  if (body !== undefined && !form) headers['Content-Type'] = 'application/json';
  const res = await fetch('/admin/api' + path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined || form ? (body as FormData | undefined) : JSON.stringify(body),
  });
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    const e: ApiError = { status: res.status, error: data?.error, field: data?.field, message: data?.message };
    throw e;
  }
  return data as T;
}
