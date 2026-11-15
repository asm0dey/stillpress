import { beforeAll, expect, test } from 'vitest';
import type { PublishState, Site } from '../api.ts';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Dashboard } = await import('./Dashboard.tsx');
  const { MemoryRouter, Route, Routes } = t.router;
  page = () => t.rtl.render(
    <MemoryRouter initialEntries={['/']}>
      <Routes><Route path="/" element={<Dashboard />} /><Route path="/login" element={<p>login page</p>} /></Routes>
    </MemoryRouter>,
  );
});

const SITE: Site = {
  title: 'My blog', homeUrl: 'https://blog.example/', language: 'en', items: 12, comments: 34, importAllowed: false,
  timezone: 'UTC', permalinkStructure: '',
};
const IDLE: PublishState = { state: 'idle', done: 0, total: 0, lastPublishedAt: null, error: null };
const ADMIN = { 'GET /session': { body: { role: 'administrator' } } };
const base = (publish: PublishState) => ({ ...ADMIN, 'GET /site': { body: SITE }, 'GET /publish': { body: publish } });

test('shows the site and its counts', async () => {
  fakeApi(base(IDLE));
  const view = page();
  expect(await view.findByRole('heading', { name: 'My blog' })).toBeTruthy();
  expect(view.getByRole('link', { name: 'View site' }).getAttribute('href')).toBe('https://blog.example/');
  expect(view.getByText('12')).toBeTruthy();
  expect(view.getByText('34')).toBeTruthy();
  expect((await view.findByRole('status')).textContent).toBe('The site is up to date.');
  expect(view.getByText('Last published: never')).toBeTruthy();
});

test('an untitled site says so and links home', async () => {
  fakeApi({ ...ADMIN, 'GET /site': { body: { ...SITE, title: '', homeUrl: '' } }, 'GET /publish': { body: IDLE } });
  const view = page();
  expect(await view.findByRole('heading', { name: 'Untitled site' })).toBeTruthy();
  expect(view.getByRole('link', { name: 'View site' }).getAttribute('href')).toBe('/');
});

test('says so when the site cannot be loaded', async () => {
  fakeApi({ ...ADMIN, 'GET /site': { status: 500 } });
  expect((await page().findByRole('alert')).textContent).toBe('Could not load the site.');
});

test('a logged-out visitor goes to the login page', async () => {
  fakeApi({ 'GET /site': { status: 401 } });
  expect(await page().findByText('login page')).toBeTruthy();
});

test('says so when the publish state cannot be loaded', async () => {
  fakeApi({ ...ADMIN, 'GET /site': { body: SITE }, 'GET /publish': { status: 500 } });
  expect((await page().findByRole('alert')).textContent).toBe('Could not load the publish state.');
});

test('queued work is announced', async () => {
  fakeApi(base({ ...IDLE, state: 'queued', lastPublishedAt: '2026-01-02T03:04:05Z' }));
  const view = page();
  expect((await view.findByRole('status')).textContent).toBe('Changes are waiting to be published.');
  expect(view.queryByText('Last published: never')).toBeNull();
});

test('a running publish shows progress and is polled until done', async () => {
  let polls = 0;
  fakeApi({
    ...ADMIN, 'GET /site': { body: SITE },
    'GET /publish': () => ({ body: polls++ === 0 ? { ...IDLE, state: 'running', done: 3, total: 10 } : IDLE }),
  });
  const view = page();
  const progress = await view.findByLabelText('Publishing… 3 of about 10 files') as HTMLProgressElement;
  expect(progress.value).toBe(3);
  expect(progress.max).toBe(10);
  expect((await view.findByRole('status', {}, { timeout: 3000 })).textContent).toBe('The site is up to date.');
});

test('a failed publish shows its error', async () => {
  fakeApi(base({ ...IDLE, state: 'failed', error: 'Disk full.' }));
  expect((await page().findByRole('alert')).textContent).toBe('Publishing failed: Disk full. It is retried in a minute.');
});

test('a failed publish without an error points at the log', async () => {
  fakeApi(base({ ...IDLE, state: 'failed' }));
  expect((await page().findByRole('alert')).textContent).toBe('Publishing failed: see the server log. It is retried in a minute.');
});

test('only administrators can republish', async () => {
  fakeApi({ ...base(IDLE), 'GET /session': { body: { role: 'editor' } } });
  const view = page();
  await view.findByRole('status');
  expect(view.queryByRole('button', { name: 'Republish site' })).toBeNull();
});

test('republishing shows the new state', async () => {
  const calls = fakeApi({ ...base(IDLE), 'POST /publish': { body: { ...IDLE, state: 'queued' } } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Republish site' }));
  expect(await view.findByText('Changes are waiting to be published.')).toBeTruthy();
  expect(calls.some((c) => c.method === 'POST' && c.path === '/publish')).toBe(true);
});

test('a failed republish says so', async () => {
  fakeApi({ ...base(IDLE), 'POST /publish': { status: 500 } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Republish site' }));
  expect((await view.findByRole('alert')).textContent).toBe('Could not start the republish.');
});

test('an expired session token on republish says to reload', async () => {
  fakeApi({ ...base(IDLE), 'POST /publish': { status: 400 } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Republish site' }));
  expect((await view.findByRole('alert')).textContent).toBe('Your session token has expired. Reload the page and try again.');
});
