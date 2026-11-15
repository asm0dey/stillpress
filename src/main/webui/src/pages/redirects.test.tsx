import { beforeAll, expect, test } from 'vitest';
import type { RedirectView } from '../api.ts';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Redirects } = await import('./Redirects.tsx');
  page = () => t.rtl.render(<t.router.MemoryRouter initialEntries={['/redirects']}><Redirects /></t.router.MemoryRouter>);
});

const OLD: RedirectView = { from: '/old/', to: '/new/', status: 301 };
const SESSION = { 'GET /session': { body: { role: 'administrator' } }, 'GET /site': { body: { importAllowed: false } } };
const LIST = { 'GET /redirects?search=&page=1': { body: { items: [OLD], total: 1 } } };

async function fill(view: ReturnType<typeof page>, from: string, to: string) {
  await t.user.type(view.getByLabelText('Old address'), from);
  await t.user.type(view.getByLabelText('New address'), to);
}

test('lists the redirects', async () => {
  fakeApi({ ...SESSION, ...LIST });
  const view = page();
  expect((await view.findByRole('status')).textContent).toBe('1 redirect');
  expect(view.getByText('/new/')).toBeTruthy();
  expect(view.queryByRole('navigation', { name: 'Pagination' })).toBeNull();
});

test('says so when the redirects cannot be loaded', async () => {
  fakeApi({ ...SESSION, 'GET /redirects?search=&page=1': { status: 500 } });
  expect((await page().findByRole('alert')).textContent).toBe('Could not load the redirects.');
});

test('searching asks for the first page of matches', async () => {
  const calls = fakeApi({ ...SESSION, ...LIST, 'GET /redirects?search=a%20b&page=1': { body: { items: [], total: 0 } } });
  const view = page();
  await view.findByText('/new/');
  await t.user.type(view.getByLabelText('Search redirects'), 'a b{Enter}');
  expect((await view.findByText('0 redirects'))).toBeTruthy();
  expect(calls.at(-1)?.path).toBe('/redirects?search=a%20b&page=1');
});

test('pages through a long list', async () => {
  fakeApi({
    ...SESSION,
    'GET /redirects?search=&page=1': { body: { items: [OLD], total: 120 } },
    'GET /redirects?search=&page=2': { body: { items: [{ ...OLD, from: '/second/' }], total: 120 } },
  });
  const view = page();
  expect(await view.findByText('Page 1 of 3')).toBeTruthy();
  expect((view.getByRole('button', { name: 'Previous' }) as HTMLButtonElement).disabled).toBe(true);
  await t.user.click(view.getByRole('button', { name: 'Next' }));
  expect(await view.findByText('/second/')).toBeTruthy();
  expect(view.getByText('Page 2 of 3')).toBeTruthy();
  await t.user.click(view.getByRole('button', { name: 'Previous' }));
  expect(await view.findByText('/old/')).toBeTruthy();
});

test('adding a redirect sends it, clears the form and reloads', async () => {
  const calls = fakeApi({ ...SESSION, ...LIST, 'POST /redirects': { status: 204 } });
  const view = page();
  await fill(view, '?p=12', 'https://example.com/');
  await t.user.selectOptions(view.getByLabelText('Type'), '302');
  await t.user.click(view.getByRole('button', { name: 'Add redirect' }));
  await t.rtl.waitFor(() => expect(calls.filter((c) => c.path.startsWith('/redirects?'))).toHaveLength(2));
  expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ from: '?p=12', to: 'https://example.com/', status: 302 });
  expect((view.getByLabelText('Old address') as HTMLInputElement).value).toBe('');
});

test('an address a post lives at marks the old address', async () => {
  fakeApi({ ...SESSION, ...LIST, 'POST /redirects': { status: 409, body: { error: 'shadows_item' } } });
  const view = page();
  await fill(view, '/about/', '/x/');
  await t.user.click(view.getByRole('button', { name: 'Add redirect' }));
  expect((await view.findByRole('alert')).textContent).toBe('A published post or page lives at this address.');
  expect(view.getByLabelText('Old address').getAttribute('aria-invalid')).toBe('true');
});

test('an invalid field gets its message', async () => {
  fakeApi({ ...SESSION, ...LIST, 'POST /redirects': { status: 400, body: { error: 'invalid', field: 'to' } } });
  const view = page();
  await fill(view, '/a/', '/a/');
  await t.user.click(view.getByRole('button', { name: 'Add redirect' }));
  expect((await view.findByRole('alert')).textContent)
    .toBe('Use a path on this site starting with /, or a full http(s) address, different from the old address.');
  expect(view.getByLabelText('New address').getAttribute('aria-invalid')).toBe('true');
});

test('a failed add says so', async () => {
  fakeApi({ ...SESSION, ...LIST, 'POST /redirects': { status: 500 } });
  const view = page();
  await fill(view, '/a/', '/b/');
  await t.user.click(view.getByRole('button', { name: 'Add redirect' }));
  expect((await view.findByRole('alert')).textContent).toBe('Could not add the redirect.');
});

test('an expired session token on add says to reload', async () => {
  fakeApi({ ...SESSION, ...LIST, 'POST /redirects': { status: 400 } });
  const view = page();
  await fill(view, '/a/', '/b/');
  await t.user.click(view.getByRole('button', { name: 'Add redirect' }));
  expect((await view.findByRole('alert')).textContent).toBe('Your session token has expired. Reload the page and try again.');
});

test('deleting a redirect sends its address and reloads', async () => {
  const calls = fakeApi({ ...SESSION, ...LIST, 'DELETE /redirects?from=%2Fold%2F': { status: 204 } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Delete redirect from /old/' }));
  await t.rtl.waitFor(() => expect(calls.filter((c) => c.path.startsWith('/redirects?search'))).toHaveLength(2));
  expect(calls.some((c) => c.method === 'DELETE' && c.path === '/redirects?from=%2Fold%2F')).toBe(true);
});

test('a failed delete says so', async () => {
  fakeApi({ ...SESSION, ...LIST, 'DELETE /redirects?from=%2Fold%2F': { status: 500 } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Delete redirect from /old/' }));
  expect((await view.findByRole('alert')).textContent).toBe('Could not delete the redirect.');
});

test('an expired session token on delete says to reload', async () => {
  fakeApi({ ...SESSION, ...LIST, 'DELETE /redirects?from=%2Fold%2F': { status: 400 } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Delete redirect from /old/' }));
  expect((await view.findByRole('alert')).textContent).toBe('Your session token has expired. Reload the page and try again.');
});
