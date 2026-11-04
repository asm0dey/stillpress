import apiFetch, { type APIFetchOptions } from '@wordpress/api-fetch';
import { type SessionUser, csrfToken } from '../api.ts';

/** The admin API's reusable block (GET /blocks/{id}; the list leaves out content). */
interface AdminBlock { id: number; title: string; content?: string | null; canEdit: boolean }
interface AdminMedia { id: number; url: string; mimeType: string | null; width: number | null; height: number | null; title: string | null; alt: string | null }

const postType = (slug: string, name: string, restBase: string) => ({
  slug, name, rest_base: restBase, rest_namespace: 'wp/v2', description: '', hierarchical: slug === 'page',
  viewable: slug === 'post' || slug === 'page', taxonomies: [], labels: { name, singular_name: name },
  supports: { title: true, editor: true, excerpt: slug !== 'wp_block' },
});
const TYPES: Record<string, ReturnType<typeof postType>> = {
  post: postType('post', 'Posts', 'posts'),
  page: postType('page', 'Pages', 'pages'),
  wp_block: postType('wp_block', 'Patterns', 'blocks'),
  attachment: postType('attachment', 'Media', 'media'),
};
const THEME = {
  stylesheet: 'plain', template: 'plain', status: 'active', name: { raw: 'Plain', rendered: 'Plain' },
  theme_supports: { 'align-wide': true, 'responsive-embeds': true, 'editor-styles': true },
};

const block = (b: AdminBlock) => ({
  id: b.id, type: 'wp_block', status: 'publish', wp_pattern_sync_status: '',
  title: { raw: b.title, rendered: b.title }, content: { raw: b.content ?? '', protected: false }, wp_pattern_category: [],
});
const media = (m: AdminMedia) => ({
  id: m.id, source_url: m.url, media_type: m.mimeType?.startsWith('image/') ? 'image' : 'file', mime_type: m.mimeType,
  alt_text: m.alt ?? '', title: { rendered: m.title ?? '' }, media_details: { width: m.width, height: m.height, sizes: {} },
});
/** core-data sends a field as a plain value or as {raw}. */
const raw = (v: unknown) => (v && typeof v === 'object' && 'raw' in v ? (v as { raw: unknown }).raw : v);

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });

function admin(path: string, method = 'GET', body?: unknown) {
  const headers: Record<string, string> = {};
  const token = csrfToken();
  if (method !== 'GET' && token) headers['X-CSRF-TOKEN'] = token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  return fetch('/admin/api' + path, {
    method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body),
  });
}

/** Our response, reshaped by fn when it is a success; errors (a 404 for one) pass through as they are. */
async function reshape<T>(res: Promise<Response>, fn: (body: T) => unknown) {
  const r = await res;
  return r.ok ? json(fn(await r.json()), r.status) : r;
}

type Route = (m: RegExpMatchArray, query: URLSearchParams, data: Record<string, unknown>) => Promise<Response> | Response;

function routes(session: SessionUser): Record<string, Record<string, Route>> {
  const save = (d: Record<string, unknown>) => ({ title: raw(d.title), content: raw(d.content) });
  // no expectedModifiedAt: the post's save writes what this editor holds, as WordPress does
  const update: Route = ([, id], _q, d) => reshape(admin('/blocks/' + id, 'PUT', save(d)), block);
  return {
    '/wp/v2/blocks': {
      // shortcut: one request per block, because the list leaves out the content the inserter parses; a list
      // with content would replace it if sites grow many reusable blocks
      GET: async () => {
        const r = await admin('/blocks');
        if (!r.ok) return r;
        const all = await Promise.all((await r.json() as AdminBlock[]).map((b) => admin('/blocks/' + b.id).then((x) => x.json())));
        return json(all.map(block));
      },
      POST: (_m, _q, d) => reshape(admin('/blocks', 'POST', save(d)), block),
      OPTIONS: () => json(null, 200, { Allow: session.role === 'contributor' ? 'GET' : 'GET, POST' }),
    },
    '/wp/v2/blocks/(\\d+)': {
      GET: ([, id]) => reshape(admin('/blocks/' + id), block),
      POST: update,
      PUT: update,
      OPTIONS: async ([, id]) => {
        const r = await admin('/blocks/' + id);
        if (!r.ok) return r;
        const b: AdminBlock = await r.json();
        return json(null, 200, { Allow: b.canEdit ? 'GET, POST, PUT, PATCH' : 'GET' });
      },
    },
    '/wp/v2/types': { GET: () => json(TYPES) },
    '/wp/v2/types/([\\w-]+)': { GET: ([, slug]) => (TYPES[slug] ? json(TYPES[slug]) : json(notFound(), 404)) },
    '/wp/v2/themes': { GET: () => json([THEME]) },
    '/wp/v2/media/(\\d+)': { GET: ([, id]) => reshape(admin('/media/' + id), media) },
    '/wp/v2/users/me': {
      GET: () => reshape<SessionUser>(admin('/session'), (s) => ({
        id: s.id, name: s.displayName || s.login, slug: s.login, roles: [s.role],
      })),
    },
    '/oembed/1.0/proxy': { GET: (_m, q) => admin('/oembed?url=' + encodeURIComponent(q.get('url') ?? '')) },
  };
}

const notFound = () => ({ code: 'rest_no_route', message: 'No route was found matching the URL and request method.', data: { status: 404 } });

/** An apiFetch error, as WordPress's REST API would send it. */
async function wpError(r: Response) {
  const b = await r.json().catch(() => null);
  return {
    code: b?.code ?? b?.error ?? 'unknown_error',
    message: b?.message ?? b?.error ?? `The request failed (${r.status}).`,
    data: { status: r.status },
  };
}

/**
 * Sends the block library's WordPress REST calls to /admin/api instead of a server: apiFetch's middlewares run first
 * (so PUT arrives as POST with X-HTTP-Method-Override), then this handler maps the path and reshapes the answer.
 * Call once, before the editor mounts.
 */
export function installWordPressApi(session: SessionUser): void {
  const table = Object.entries(routes(session)).map(([p, byMethod]) => [new RegExp('^' + p + '/?$'), byMethod] as const);
  apiFetch.setFetchHandler(async (options: APIFetchOptions) => {
    const headers = (options.headers ?? {}) as Record<string, string>;
    const method = (headers['X-HTTP-Method-Override'] ?? options.method ?? 'GET').toUpperCase();
    const u = new URL(options.url ?? options.path ?? '', 'http://localhost');
    const path = u.pathname.replace(/^\/wp-json/, '');
    let response: Response | undefined;
    for (const [re, byMethod] of table) {
      const m = path.match(re);
      const route = m && byMethod[method];
      if (route) {
        response = await route(m, u.searchParams, (options.data ?? {}) as Record<string, unknown>);
        break;
      }
    }
    if (!response) {
      console.warn('Unknown WordPress API call: ' + method + ' ' + path);
      throw notFound();
    }
    if (options.parse === false) {
      if (!response.ok) throw response;
      return response;
    }
    if (!response.ok) throw await wpError(response);
    return response.status === 204 ? null : response.json();
  });
}
