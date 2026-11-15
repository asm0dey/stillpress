import { beforeAll, expect, test } from 'vitest';
import type { ItemSummary } from '../api.ts';
import { formatInstant, formatLocal } from '../i18n/index.ts';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: (type?: 'post' | 'page') => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Items } = await import('./Items.tsx');
  const { MemoryRouter, Route, Routes } = t.router;
  page = (type = 'post') => t.rtl.render(
    <MemoryRouter initialEntries={['/items']}>
      <Routes>
        <Route path="/items" element={<Items type={type} />} />
        <Route path="/edit/:id" element={<p>editing item</p>} />
      </Routes>
    </MemoryRouter>,
  );
});

const HELLO: ItemSummary = {
  id: 1, type: 'post', title: 'Hello', status: 'publish', author: 'ada', date: '2026-11-01T10:00:00', modified: '2026-11-01T10:00:00Z', permalink: null,
};
const DRAFT: ItemSummary = { ...HELLO, id: 2, title: '', status: 'draft', author: null, date: null, modified: '2026-11-02T09:30:00Z' };
const GONE: ItemSummary = { ...HELLO, id: 3, title: 'Gone', status: 'trash' };
const SESSION = { 'GET /session': { body: { role: 'author' } }, 'GET /site': { body: { importAllowed: false, timezone: 'Europe/Berlin' } } };
const list = (items: ItemSummary[], total = items.length, counts: Record<string, number> = { publish: 1, draft: 1, trash: 1 }) =>
  ({ body: { items, total, counts } });
const ALL = '/items?type=post&status=&search=&page=1';
const POSTS = { ...SESSION, [`GET ${ALL}`]: list([HELLO, DRAFT, GONE]) };

const rows = (view: ReturnType<typeof page>) =>
  view.getAllByRole('row').slice(1).map((r) => [...r.querySelectorAll('td')].slice(0, 4).map((c) => c.textContent));

test('lists the posts with their tabs and counts', async () => {
  fakeApi(POSTS);
  const view = page();
  expect((await view.findByRole('status')).textContent).toBe('3 posts');
  await t.rtl.waitFor(() => expect(rows(view)[1][3]).toBe(formatInstant(DRAFT.modified, 'Europe/Berlin')));
  expect(rows(view)).toEqual([
    ['Hello', 'ada', 'Published', formatLocal(HELLO.date!)],
    ['(no title)', '—', 'Draft', formatInstant(DRAFT.modified, 'Europe/Berlin')],
    ['Gone', 'ada', 'Trash', formatLocal(HELLO.date!)],
  ]);
  expect(view.getByRole('link', { name: 'Hello' }).getAttribute('href')).toBe('/edit/1');
  expect(view.queryByRole('link', { name: 'Gone' })).toBeNull();
  // tabs without items are hidden; All leaves the trash out
  const tabs = t.rtl.within(view.getByRole('navigation', { name: 'Filter by status' })).getAllByRole('button');
  expect(tabs.map((b) => b.textContent)).toEqual(['All (2)', 'Drafts (1)', 'Published (1)', 'Trash (1)']);
});

test('pages get their own headings', async () => {
  fakeApi({ ...SESSION, 'GET /items?type=page&status=&search=&page=1': list([{ ...HELLO, type: 'page' }], 1) });
  const view = page('page');
  expect((await view.findByRole('status')).textContent).toBe('1 page');
  expect(view.getByRole('heading', { name: 'Pages' })).toBeTruthy();
  expect(view.getByLabelText('Search pages')).toBeTruthy();
  expect(view.getByRole('button', { name: 'Add new page' })).toBeTruthy();
});

test('says so when the list cannot be loaded', async () => {
  fakeApi({ ...SESSION, [`GET ${ALL}`]: { status: 500 } });
  expect((await page().findByRole('alert')).textContent).toBe('Could not load the list.');
});

test('a tab loads its status', async () => {
  const calls = fakeApi({ ...POSTS, 'GET /items?type=post&status=draft&search=&page=1': list([DRAFT], 1) });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Drafts (1)' }));
  await t.rtl.waitFor(() => expect(view.getByRole('status').textContent).toBe('1 post'));
  expect(view.getByRole('button', { name: 'Drafts (1)' }).getAttribute('aria-pressed')).toBe('true');
  expect(calls.at(-1)?.path).toBe('/items?type=post&status=draft&search=&page=1');
});

test('a search sends the encoded query', async () => {
  const calls = fakeApi({ ...POSTS, 'GET /items?type=post&status=&search=a%20b&page=1': list([], 0, {}) });
  const view = page();
  await view.findByText('Hello');
  await t.user.type(view.getByLabelText('Search posts'), 'a b{Enter}');
  await t.rtl.waitFor(() => expect(view.getByRole('status').textContent).toBe('0 posts'));
  expect(calls.at(-1)?.path).toBe('/items?type=post&status=&search=a%20b&page=1');
});

test('pages through a long list', async () => {
  fakeApi({
    ...SESSION,
    [`GET ${ALL}`]: list([HELLO], 45, { publish: 45 }),
    'GET /items?type=post&status=&search=&page=2': list([{ ...HELLO, id: 21, title: 'Second' }], 45, { publish: 45 }),
  });
  const view = page();
  expect(await view.findByText('Page 1 of 3')).toBeTruthy();
  expect((view.getByRole('button', { name: 'Previous' }) as HTMLButtonElement).disabled).toBe(true);
  await t.user.click(view.getByRole('button', { name: 'Next' }));
  expect(await view.findByText('Second')).toBeTruthy();
  expect(view.getByText('Page 2 of 3')).toBeTruthy();
  await t.user.click(view.getByRole('button', { name: 'Previous' }));
  expect(await view.findByText('Hello')).toBeTruthy();
});

test('adding one opens it in the editor', async () => {
  const calls = fakeApi({ ...POSTS, 'POST /items': { status: 201, body: { id: 9 } } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Add new post' }));
  expect(await view.findByText('editing item')).toBeTruthy();
  expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ type: 'post' });
});

test.each([
  [403, 'You cannot add one.'],
  [500, 'Could not add one.'],
])('an add answered %i says %s', async (status, text) => {
  fakeApi({ ...POSTS, 'POST /items': { status } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Add new post' }));
  expect((await view.findByRole('alert')).textContent).toBe(text);
});

test('trashing and restoring reload the list', async () => {
  const calls = fakeApi({ ...POSTS, 'DELETE /items/1': { status: 204 }, 'POST /items/3/restore': { status: 204 } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Move “Hello” to the trash' }));
  await t.rtl.waitFor(() => expect(calls.filter((c) => c.path === ALL)).toHaveLength(2));
  await t.user.click(view.getByRole('button', { name: 'Restore “Gone”' }));
  await t.rtl.waitFor(() => expect(calls.filter((c) => c.path === ALL)).toHaveLength(3));
  expect(calls.filter((c) => c.method !== 'GET').map((c) => `${c.method} ${c.path}`)).toEqual(['DELETE /items/1', 'POST /items/3/restore']);
  expect(view.getByRole('button', { name: 'Move “untitled” to the trash' })).toBeTruthy();
});

test.each([
  [{ status: 403 }, 'You cannot change this one.'],
  [{ status: 400 }, 'Your session token has expired. Reload the page and try again.'],
  [{ status: 500 }, 'Could not save the change.'],
])('a trash answered %j says %s', async (reply, text) => {
  fakeApi({ ...POSTS, 'DELETE /items/1': reply });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Move “Hello” to the trash' }));
  expect((await view.findByRole('alert')).textContent).toBe(text);
});
