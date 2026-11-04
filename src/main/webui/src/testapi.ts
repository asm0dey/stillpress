/// <reference types="bun" />
import { afterEach } from 'bun:test';
import * as rtl from '@testing-library/react/pure';
import { userEvent } from '@testing-library/user-event';
import * as router from 'react-router';

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

export interface Pages {
  rtl: typeof rtl;
  user: typeof userEvent;
  router: typeof router;
}

/**
 * What a page test renders with: Testing Library, user-event and the router. After each test the page unmounts and
 * fetch is real again. The pure entry, with its setup done here: the main one registers cleanup only where afterEach
 * is a global, which bun's is not.
 */
export function usePages(): Pages {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  const realFetch = globalThis.fetch;
  afterEach(() => {
    rtl.cleanup();
    globalThis.fetch = realFetch;
  });
  return { rtl, user: userEvent, router };
}
