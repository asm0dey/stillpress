import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { api, csrfToken } from './api.ts';

interface Sent { method: string; url: string; token: string | undefined }
let sent: Sent[];
let reply: (s: Sent) => Response;

beforeEach(() => {
  localStorage.clear();
  sent = [];
  globalThis.fetch = vi.fn(async (url: string, init: RequestInit = {}) => {
    const s = { method: init.method ?? 'GET', url, token: (init.headers as Record<string, string>)['X-CSRF-TOKEN'] };
    sent.push(s);
    return reply(s);
  }) as unknown as typeof fetch;
});

afterEach(() => vi.restoreAllMocks());

const issuing = (token: string) => () => new Response('{}', { status: 200, headers: { 'X-CSRF-TOKEN': token } });

test('the token from a response header goes out with the next request', async () => {
  reply = issuing('t1');
  await api('/session');
  await api('/logout', { method: 'POST' });
  expect(sent).toEqual([
    { method: 'GET', url: '/admin/api/session', token: undefined },
    { method: 'POST', url: '/admin/api/logout', token: 't1' },
  ]);
  expect(csrfToken()).toBe('t1');
});

test('a write without a token fetches one with GET /setup first', async () => {
  reply = issuing('t2');
  await api('/logout', { method: 'POST' });
  expect(sent).toEqual([
    { method: 'GET', url: '/admin/api/setup', token: undefined },
    { method: 'POST', url: '/admin/api/logout', token: 't2' },
  ]);
});

test('a GET refused for a stale token retries without it', async () => {
  localStorage.setItem('stillpress-csrf-token', 'stale');
  reply = (s) => (s.token === 'stale' ? new Response(null, { status: 400 }) : issuing('fresh')());
  await api('/session');
  expect(sent.map((s) => s.token)).toEqual(['stale', undefined]);
  expect(csrfToken()).toBe('fresh');
});
