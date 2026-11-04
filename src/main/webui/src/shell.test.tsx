/// <reference types="bun" />
import { beforeAll, expect, test } from 'bun:test';
import { fakeApi, usePages } from './testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Shell } = await import('./Shell.tsx');
  const { MemoryRouter, Route, Routes } = t.router;
  page = () => t.rtl.render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<Shell><p>content</p></Shell>} />
        <Route path="/login" element={<p>login page</p>} />
      </Routes>
    </MemoryRouter>,
  );
});

const links = (view: ReturnType<typeof page>) => view.getAllByRole('link').map((a) => a.textContent);
const as = (role: string, importAllowed = true) => ({
  'GET /session': { body: { role } },
  'GET /site': { body: { importAllowed } },
});

test('an administrator gets every screen', async () => {
  fakeApi(as('administrator'));
  const view = page();
  await view.findByRole('link', { name: 'Import' });
  expect(links(view)).toEqual(['Stillpress', 'Posts', 'Pages', 'Reusable blocks', 'Categories', 'Tags', 'Redirects', 'Users',
    'Settings', 'Import', 'Profile']);
  expect(view.getByText('content')).toBeTruthy();
});

test('import is hidden once the site no longer allows it', async () => {
  fakeApi(as('administrator', false));
  const view = page();
  await view.findByRole('link', { name: 'Settings' });
  expect(view.queryByRole('link', { name: 'Import' })).toBeNull();
});

test('an editor also gets pages and the taxonomies, not the administrator screens', async () => {
  fakeApi(as('editor'));
  const view = page();
  await view.findByRole('link', { name: 'Posts' });
  expect(links(view)).toEqual(['Stillpress', 'Posts', 'Pages', 'Reusable blocks', 'Categories', 'Tags', 'Profile']);
});

test('an author gets posts, reusable blocks and the profile', async () => {
  fakeApi(as('author'));
  const view = page();
  await view.findByRole('link', { name: 'Posts' });
  expect(links(view)).toEqual(['Stillpress', 'Posts', 'Reusable blocks', 'Profile']);
});

test('logging out goes to the login page, even when the call fails', async () => {
  const calls = fakeApi({ ...as('author'), 'POST /logout': { status: 500 } });
  const view = page();
  await t.user.click(view.getByRole('button', { name: 'Log out' }));
  expect(await view.findByText('login page')).toBeTruthy();
  expect(calls.some((c) => c.method === 'POST' && c.path === '/logout')).toBe(true);
});

test('a refused language change says so', async () => {
  const calls = fakeApi({ ...as('author'), 'PUT /session/locale': { status: 500 } });
  const view = page();
  await t.user.selectOptions(view.getByLabelText('Language'), 'ru');
  expect((await view.findByRole('alert')).textContent).toBe('Could not change the language.');
  expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ locale: 'ru' });
});

test('an expired session token on the language change says to reload', async () => {
  fakeApi({ ...as('author'), 'PUT /session/locale': { status: 400 } });
  const view = page();
  await t.user.selectOptions(view.getByLabelText('Language'), 'ru');
  expect((await view.findByRole('alert')).textContent).toBe('Your session token has expired. Reload the page and try again.');
});
