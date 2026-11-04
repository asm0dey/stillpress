export type Role = 'administrator' | 'editor' | 'author' | 'contributor';

/** The body of a non-2xx response, plus its status. */
/** Shown when the server refuses a call for a missing or stale CSRF token. */
export const CSRF_MESSAGE = 'Your session token has expired. Reload the page and try again.';

export interface ApiError {
  status: number;
  error?: string;
  field?: string;
  message?: string;
  /** with error 'unsafe_html': what the author's role may not save */
  violations?: string[];
}

/** GET /session */
export interface SessionUser { id: number; login: string; displayName: string; role: Role }
/** GET /site */
export interface Site {
  title: string; homeUrl: string; language: string; items: number; comments: number; importAllowed: boolean;
  timezone: string; permalinkStructure: string;
}
export type ItemStatus = 'draft' | 'pending' | 'future' | 'publish' | 'private' | 'trash';
/** GET /items/{id}; date is in the site's time zone */
export interface Item {
  id: number; type: 'post' | 'page'; title: string; slug: string | null; status: ItemStatus; body: string;
  bodyFormat: 'blocks' | 'classic'; excerpt: string; date: string | null; permalink: string | null;
  categories: number[]; tags: number[]; authorId: number | null; author: string | null; parentId: number | null;
  commentStatus: 'open' | 'closed'; menuOrder: number | null; modifiedAt: string; autosave: { createdAt: string } | null; canEdit: boolean;
}
/** One row of GET /items */
export interface ItemSummary {
  id: number; type: 'post' | 'page'; title: string; status: ItemStatus; author: string | null;
  date: string | null; modified: string; permalink: string | null;
}
/** One row of GET /blocks; GET, POST and PUT /blocks/{id} add content and autosave */
export interface ReusableBlock {
  id: number; title: string; content?: string | null; author: string | null; modifiedAt: string; usedBy: number;
  canEdit: boolean; autosave?: { createdAt: string } | null;
  /** only in PUT's answer: the published items that save queued for republishing */
  republished?: number;
}
/** GET /media/{id}, POST /media */
export interface Media {
  id: number; url: string | null; mimeType: string | null; width: number | null; height: number | null; title: string | null; alt: string | null;
}
/** GET and PUT /settings */
export interface SiteSettings {
  title: string; description: string; language: string; homeUrl: string; timezone: string;
  postsPerPage: number; postsPerRss: number; permalinkStructure: string; categoryBase: string; tagBase: string;
  defaultCategory: number | null; defaultCommentStatus: 'open' | 'closed';
}
/** GET /terms */
export interface TermView { id: number; name: string; slug: string; parentId: number | null; description?: string | null; count?: number }
/** GET /redirects */
export interface RedirectView { from: string; to: string; status: 301 | 302 }
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

/** The csrf-token cookie (set by any API GET), which every non-GET echoes in X-CSRF-TOKEN. */
export function csrfToken(): string | undefined {
  return document.cookie.match(/(?:^|; )csrf-token=([^;]*)/)?.[1];
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
  const token = csrfToken();
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
    const e: ApiError = { status: res.status, error: data?.error, field: data?.field, message: data?.message,
      violations: data?.violations };
    // every 400 our endpoints send names an error; a bare one is the CSRF guard's refusal
    if (res.status === 400 && !e.error && !e.field) {
      e.error = 'csrf';
      e.message = CSRF_MESSAGE;
    }
    throw e;
  }
  return data as T;
}
