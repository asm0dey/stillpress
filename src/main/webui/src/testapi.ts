/// <reference types="bun" />

interface Reply { status?: number; body?: unknown }
/** A reply, or one computed from the request's JSON body. */
type Route = Reply | ((body: unknown) => Reply);
export interface Call { method: string; path: string; body: unknown }

/**
 * Replaces fetch with the admin API in {@code routes}, keyed like "GET /users" (the path after /admin/api); any other
 * request answers 404 not_found. Returns the requests as they come, so a test can check what was sent.
 */
export function fakeApi(routes: Record<string, Route>): Call[] {
  const calls: Call[] = [];
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const path = url.replace(/^\/admin\/api/, '');
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body;
    calls.push({ method, path, body });
    const route = routes[`${method} ${path}`];
    const reply = typeof route === 'function' ? route(body) : route ?? { status: 404, body: { error: 'not_found' } };
    const status = reply.status ?? 200;
    return new Response(status === 204 || reply.body === undefined ? null : JSON.stringify(reply.body), { status });
  }) as typeof fetch;
  return calls;
}
