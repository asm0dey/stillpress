import { afterEach, beforeAll, beforeEach, expect, test, vi } from 'vitest';
import apiFetch from '@wordpress/api-fetch';
import { installWordPressApi } from './wpapi.ts';

interface Call { url: string; method: string; headers: Record<string, string>; body: unknown }
let calls: Call[];
let reply: (url: string) => Response;

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const call = (options: Record<string, unknown>) => apiFetch(options) as Promise<any>;

beforeAll(() => {
  document.cookie = 'a=b';
  document.cookie = 'csrf-token=tok';
});

beforeEach(() => {
  calls = [];
  reply = () => json({});
  globalThis.fetch = vi.fn(async (url: string, init: RequestInit = {}) => {
    calls.push({
      url, method: init.method ?? 'GET', headers: (init.headers ?? {}) as Record<string, string>,
      body: init.body ? JSON.parse(init.body as string) : undefined,
    });
    return reply(url);
  }) as unknown as typeof fetch;
  installWordPressApi({ id: 7, login: 'ann', email: 'u@example.org', displayName: 'Ann', role: 'author', locale: 'en' });
});

afterEach(() => vi.restoreAllMocks());

const BLOCK = { id: 3, title: 'Promo', content: '<!-- wp:paragraph --><p>x</p><!-- /wp:paragraph -->', canEdit: true };
const WP_BLOCK = {
  id: 3, type: 'wp_block', status: 'publish', wp_pattern_sync_status: '', title: { raw: 'Promo', rendered: 'Promo' },
  content: { raw: BLOCK.content, protected: false }, wp_pattern_category: [],
};

test('block list and one block are reshaped to wp_block records', async () => {
  reply = (url) => json(url.endsWith('?content=true') ? [BLOCK] : BLOCK);
  // one request for the list, content included
  expect(await call({ path: '/wp/v2/blocks?per_page=-1&context=edit' })).toEqual([WP_BLOCK]);
  expect(await call({ path: '/wp/v2/blocks/3?context=edit' })).toEqual(WP_BLOCK);
  expect(calls.map((c) => c.method + ' ' + c.url)).toEqual(['GET /admin/api/blocks?content=true', 'GET /admin/api/blocks/3']);
});

test('creating a block posts title and content with the CSRF header', async () => {
  reply = () => json(BLOCK, 201);
  expect(await call({ path: '/wp/v2/blocks', method: 'POST', data: { title: 'Promo', content: BLOCK.content, status: 'publish' } }))
    .toEqual(WP_BLOCK);
  expect(calls).toEqual([{
    url: '/admin/api/blocks', method: 'POST', headers: { 'X-CSRF-TOKEN': 'tok', 'Content-Type': 'application/json' },
    body: { title: 'Promo', content: BLOCK.content },
  }]);
});

test('a 403 on create surfaces as an apiFetch error with its status', async () => {
  reply = () => json({ error: 'forbidden' }, 403);
  await expect(call({ path: '/wp/v2/blocks', method: 'POST', data: { content: 'x' } }))
    .rejects.toEqual({ code: 'forbidden', message: 'forbidden', data: { status: 403 } });
});

test('an unsafe_html refusal keeps its violations for the editor to list', async () => {
  reply = () => json({ error: 'unsafe_html', field: 'body', violations: ['<script> element'] }, 400);
  await expect(call({ path: '/wp/v2/blocks', method: 'POST', data: { content: '<script></script>' } }))
    .rejects.toEqual({ code: 'unsafe_html', message: 'unsafe_html', data: { status: 400, violations: ['<script> element'] } });
});

test('OPTIONS answers Allow from the role and the block\'s canEdit', async () => {
  const allow = async (path: string) => ((await call({ path, method: 'OPTIONS', parse: false })) as Response).headers.get('allow');
  expect(await allow('/wp/v2/blocks')).toEqual('GET, POST');
  reply = () => json({ ...BLOCK, canEdit: false });
  expect(await allow('/wp/v2/blocks/3')).toEqual('GET');
  reply = () => json(BLOCK);
  expect(await allow('/wp/v2/blocks/3')).toEqual('GET, POST, PUT, PATCH');
  installWordPressApi({ id: 8, login: 'con', email: 'u@example.org', displayName: 'Con', role: 'contributor', locale: 'en' });
  expect(await allow('/wp/v2/blocks')).toEqual('GET');
  expect(calls.map((c) => c.url)).toEqual(['/admin/api/blocks/3', '/admin/api/blocks/3']);
});

test('a block\'s GET carries Allow too, since core-data reads canUser from it', async () => {
  const allow = async () => ((await call({ path: '/wp/v2/blocks/3?context=edit', parse: false })) as Response).headers.get('allow');
  reply = () => json({ ...BLOCK, canEdit: false });
  expect(await allow()).toEqual('GET');
  reply = () => json(BLOCK);
  expect(await allow()).toEqual('GET, POST, PUT, PATCH');
});

test('types and the active theme are static', async () => {
  const types = await call({ path: '/wp/v2/types?context=view' });
  expect(Object.keys(types)).toEqual(['post', 'page', 'wp_block', 'attachment']);
  expect(types.wp_block.rest_base).toEqual('blocks');
  expect((await call({ path: '/wp/v2/types/attachment' })).rest_base).toEqual('media');
  const [theme] = await call({ path: '/wp/v2/themes?status=active' });
  expect(theme.stylesheet).toEqual('plain');
  expect(theme.theme_supports).toEqual({ 'align-wide': true, 'responsive-embeds': true, 'editor-styles': true });
  expect(calls).toEqual([]);
});

test('media is reshaped and a 404 passes through', async () => {
  reply = () => json({ id: 5, url: '/media/2026/11/a.png', mimeType: 'image/png', width: 3, height: 2, title: 'A', alt: 'alt' });
  expect(await call({ path: '/wp/v2/media/5?context=view' })).toEqual({
    id: 5, source_url: '/media/2026/11/a.png', media_type: 'image', mime_type: 'image/png', alt_text: 'alt',
    title: { rendered: 'A' }, media_details: { width: 3, height: 2, sizes: {} },
  });
  reply = () => json({ error: 'not_found' }, 404);
  await expect(call({ path: '/wp/v2/media/6' })).rejects.toEqual({ code: 'not_found', message: 'not_found', data: { status: 404 } });
  expect(calls.map((c) => c.url)).toEqual(['/admin/api/media/5', '/admin/api/media/6']);
});

test('users/me comes from the session', async () => {
  reply = () => json({ id: 7, login: 'ann', email: 'u@example.org', displayName: 'Ann', role: 'author', locale: 'en' });
  expect(await call({ path: '/wp/v2/users/me' })).toEqual({ id: 7, name: 'Ann', slug: 'ann', roles: ['author'] });
  expect(calls.map((c) => c.url)).toEqual(['/admin/api/session']);
});

test('the oEmbed proxy goes to /admin/api/oembed', async () => {
  reply = () => json({ type: 'video', html: '<iframe></iframe>' });
  const url = 'https://www.youtube.com/watch?v=x&t=1';
  expect(await call({ path: '/oembed/1.0/proxy?url=' + encodeURIComponent(url) })).toEqual({ type: 'video', html: '<iframe></iframe>' });
  expect(calls.map((c) => c.url)).toEqual(['/admin/api/oembed?url=' + encodeURIComponent(url)]);
});

test('an unknown call warns and fails with rest_no_route', async () => {
  const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
  await expect(call({ path: '/wp/v2/block-renderer/core/archives?context=edit', method: 'POST' })).rejects.toMatchObject({ code: 'rest_no_route' });
  expect(warn).toHaveBeenCalledWith('Unknown WordPress API call: POST /wp/v2/block-renderer/core/archives');
  expect(calls).toEqual([]);
});
