export type Role = 'administrator' | 'editor' | 'author' | 'contributor';

/** The body of a non-2xx response, plus its status. */
/** Shown when the server refuses a call for a missing or stale CSRF token. */
export const CSRF_MESSAGE = 'Your session token has expired. Reload the page and try again.';

export interface ApiError {
  status: number;
  error?: string;
  field?: string;
  message?: string;
}

/** GET /session */
export interface SessionUser { id: number; login: string; displayName: string; role: Role }
/** GET /site */
export interface Site { title: string; homeUrl: string; language: string; items: number; comments: number; importAllowed: boolean }
/** GET and PUT /settings */
export interface SiteSettings {
  title: string; description: string; language: string; homeUrl: string; timezone: string;
  postsPerPage: number; postsPerRss: number; permalinkStructure: string; categoryBase: string; tagBase: string;
  defaultCategory: number | null; defaultCommentStatus: 'open' | 'closed';
}
/** GET /terms */
export interface TermView { id: number; name: string; slug: string; parentId: number | null }
/** GET /users */
export interface UserView {
  id: number; login: string; displayName: string; email: string; role: Role; hasPassword: boolean;
}
/** GET and POST /publish; total is an estimate, so done may pass it */
export interface PublishState {
  state: 'idle' | 'queued' | 'running' | 'failed';
  done: number;
  total: number;
  lastPublishedAt: string | null;
  error: string | null;
}
/** GET and POST /import */
export interface ImportStatus {
  state: 'idle' | 'running' | 'done' | 'failed' | 'closed';
  items: number;
  media: number;
  report: string | null;
  error: string | null;
  contentLeft: boolean;
}

/**
 * Calls the admin JSON API. Every request echoes the csrf-token cookie (set by any API GET) in X-CSRF-TOKEN; a
 * FormData body goes as multipart (the browser sets its Content-Type), anything else as JSON. Non-2xx throws an
 * ApiError; T is the JSON response type (void for 204).
 */
export async function api<T = void>(
  path: string,
  { method = 'GET', body }: { method?: string; body?: unknown } = {},
): Promise<T> {
  const headers: Record<string, string> = {};
  const token = document.cookie.match(/(?:^|; )csrf-token=([^;]*)/)?.[1];
  if (token) headers['X-CSRF-TOKEN'] = token;
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
    // every 400 our endpoints send names an error; a bare one is the CSRF guard's refusal
    if (res.status === 400 && !e.error && !e.field) {
      e.error = 'csrf';
      e.message = CSRF_MESSAGE;
    }
    throw e;
  }
  return data as T;
}
