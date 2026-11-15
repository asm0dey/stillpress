import { __ } from '@wordpress/i18n';
import { type Locale } from './i18n/index.ts';

export type Role = 'administrator' | 'editor' | 'author' | 'contributor';

const ADMINISTRATOR: Role[] = ['administrator'];
const EDITORS: Role[] = ['administrator', 'editor'];
/** Screens only some roles may use, by first path segment, as Capabilities decides on the server. */
const SCREEN_ROLES: Record<string, Role[]> = {
  users: ADMINISTRATOR, settings: ADMINISTRATOR, security: ADMINISTRATOR, import: ADMINISTRATOR, redirects: ADMINISTRATOR,
  pages: EDITORS, categories: EDITORS, tags: EDITORS,
};

/** Whether the role may open the admin screen at this path; the gate sends it to the dashboard otherwise. */
export const mayOpen = (role: Role, path: string): boolean => SCREEN_ROLES[path.split('/')[1]]?.includes(role) ?? true;

/** The body of a non-2xx response, plus its status. */
/** Shown when the server refuses a call for a missing or stale CSRF token. */
export const CSRF_MESSAGE = () => __('Your session token has expired. Reload the page and try again.', 'stillpress');

export interface ApiError {
  status: number;
  error?: string;
  field?: string;
  message?: string;
  /** with error 'unsafe_html': what the author's role may not save */
  violations?: string[];
}

/** Whether a thrown value is the ApiError {@link api} throws, not a network failure or a bug. */
export function isApiError(e: unknown): e is ApiError {
  return typeof e === 'object' && e !== null && typeof (e as { status?: unknown }).status === 'number';
}

/** The ApiError, or status 0 for anything else thrown (a network failure), which callers show as a generic failure. */
export const asApiError = (e: unknown): ApiError => (isApiError(e) ? e : { status: 0 });

/** GET /session */
export interface SessionUser { id: number; login: string; displayName: string; email: string; role: Role; locale: Locale }
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
  title: string; description: string; language: string; homeUrl: string; adminUrl: string; timezone: string;
  postsPerPage: number; postsPerRss: number; permalinkStructure: string; categoryBase: string; tagBase: string;
  defaultCategory: number | null; defaultCommentStatus: 'open' | 'closed';
}
/** GET and PUT /mail; PUT also takes password: null keeps the stored one, '' deletes it */
export interface MailSettings {
  host: string; port: number; security: 'none' | 'starttls' | 'tls'; username: string; from: string; passwordSet: boolean;
  lastError: string | null;
}
/** GET and PUT /captcha; PUT takes turnstileSecret instead of secretSet, null keeping the stored one */
export interface CaptchaSettings {
  provider: 'none' | 'turnstile' | 'altcha'; turnstileSiteKey: string | null; secretSet: boolean; locked: boolean;
}
/** GET /terms */
export interface TermView { id: number; name: string; slug: string; parentId: number | null; description?: string | null; count?: number }
/** GET /redirects */
export interface RedirectView { from: string; to: string; status: 301 | 302 }
/** GET /users */
export interface UserView {
  id: number; login: string; displayName: string; email: string; role: Role; hasPassword: boolean; twoFactor: boolean;
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

const CSRF_KEY = 'stillpress-csrf-token';

/**
 * The raw CSRF token from the latest X-CSRF-TOKEN response header; the csrf-token cookie holds only its signature.
 * Kept in localStorage so tabs share it: a tab without one makes the server issue a new token, stranding the rest.
 */
export function csrfToken(): string | undefined {
  return localStorage.getItem(CSRF_KEY) ?? undefined;
}

/**
 * fetch for /admin/api: sends the CSRF token (a write without one fetches it with GET /setup first) and keeps the one
 * the answer carries. A GET refused for a stale token (cookies signed by an earlier keyset) retries without it.
 */
export async function adminFetch(
  path: string,
  init: Omit<RequestInit, 'headers'> & { headers?: Record<string, string> } = {},
): Promise<Response> {
  const method = init.method ?? 'GET';
  if (method !== 'GET' && !csrfToken()) await adminFetch('/setup');
  const token = csrfToken();
  const headers = { ...init.headers };
  if (token) headers['X-CSRF-TOKEN'] = token;
  const res = await fetch('/admin/api' + path, { ...init, method, headers, credentials: 'same-origin' });
  if (method === 'GET' && res.status === 400 && token) {
    localStorage.removeItem(CSRF_KEY);
    return adminFetch(path, init);
  }
  const next = res.headers.get('X-CSRF-TOKEN');
  if (next) localStorage.setItem(CSRF_KEY, next);
  return res;
}

/**
 * Calls the admin JSON API through adminFetch, so every request carries the CSRF token; a FormData body goes as
 * multipart (the browser sets its Content-Type), anything else as JSON. Non-2xx throws an ApiError; T is the JSON
 * response type (void for 204).
 */
export async function api<T = void>(
  path: string,
  { method = 'GET', body }: { method?: string; body?: unknown } = {},
): Promise<T> {
  const headers: Record<string, string> = {};
  const form = body instanceof FormData;
  if (body !== undefined && !form) headers['Content-Type'] = 'application/json';
  const res = await adminFetch(path, {
    method,
    headers,
    body: body === undefined || form ? (body as FormData | undefined) : JSON.stringify(body),
  });
  const data = await res.json().catch(() => null);
  if (!res.ok) {
    const e: ApiError = { status: res.status, error: data?.error, field: data?.field, message: data?.message,
      violations: data?.violations };
    // every 400 our endpoints send names an error; a bare one is the CSRF guard's refusal
    if (res.status === 400 && !e.error && !e.field) {
      e.error = 'csrf';
      e.message = CSRF_MESSAGE();
    }
    throw e;
  }
  return data as T;
}
