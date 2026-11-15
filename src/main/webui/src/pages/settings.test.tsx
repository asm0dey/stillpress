import { beforeAll, expect, test } from 'vitest';
import type { SiteSettings } from '../api.ts';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Settings } = await import('./Settings.tsx');
  const { MemoryRouter, Route, Routes } = t.router;
  page = () => t.rtl.render(
    <MemoryRouter initialEntries={['/settings']}>
      <Routes>
        <Route path="/settings" element={<Settings />} />
        <Route path="/" element={<p>dashboard</p>} />
      </Routes>
    </MemoryRouter>,
  );
});

const SETTINGS: SiteSettings = {
  title: 'My blog', description: 'Notes', language: 'en', homeUrl: 'https://example.org', adminUrl: 'https://admin.example.org',
  timezone: 'Europe/Berlin', postsPerPage: 10, postsPerRss: 10, permalinkStructure: '/%postname%/', categoryBase: 'category',
  tagBase: 'tag', defaultCategory: null, defaultCommentStatus: 'open',
};
const SESSION = { 'GET /session': { body: { role: 'administrator' } }, 'GET /site': { body: { importAllowed: false } } };
const LOADED = {
  ...SESSION, 'GET /settings': { body: SETTINGS },
  'GET /terms?taxonomy=category': { body: [{ id: 7, name: 'News', slug: 'news', parentId: null }] },
};
const input = (view: ReturnType<typeof page>, label: string) => view.getByLabelText(label) as HTMLInputElement;

test('shows the saved settings', async () => {
  fakeApi(LOADED);
  const view = page();
  expect((await view.findByLabelText('Site title') as HTMLInputElement).value).toBe('My blog');
  expect(input(view, 'Time zone').value).toBe('Europe/Berlin');
  expect(input(view, 'Structure').value).toBe('/%postname%/');
  expect(await view.findByRole('option', { name: 'News' })).toBeTruthy();
  expect(view.getByLabelText('Type the site title (My blog) to confirm')).toBeTruthy();
});

test('a structure that is no preset shows as custom', async () => {
  fakeApi({ ...LOADED, 'GET /settings': { body: { ...SETTINGS, permalinkStructure: '/blog/%postname%/' } } });
  const view = page();
  await view.findByLabelText('Site title');
  expect(input(view, 'Structure').value).toBe('custom');
});

test('says so when the settings cannot be loaded', async () => {
  fakeApi({ ...SESSION, 'GET /settings': { status: 500 } });
  expect((await page().findByRole('alert')).textContent).toBe('Could not load the settings.');
});

test('saving sends every field and says it is publishing', async () => {
  const calls = fakeApi({ ...LOADED, 'PUT /settings': (body) => ({ body }) });
  const view = page();
  const title = await view.findByLabelText('Site title');
  await t.user.clear(title);
  await t.user.type(title, 'New blog');
  await t.user.clear(input(view, 'Posts per page'));
  await t.user.type(input(view, 'Posts per page'), '20');
  await t.user.clear(input(view, 'Posts per feed'));
  await t.user.type(input(view, 'Posts per feed'), '5');
  await t.user.selectOptions(view.getByLabelText('Structure'), 'Numeric');
  await t.user.selectOptions(view.getByLabelText('Default post category'), 'News');
  await t.user.selectOptions(view.getByLabelText('Comments on new items'), 'Closed');
  await t.user.click(view.getByRole('button', { name: 'Save settings' }));
  expect((await view.findByRole('status')).textContent).toBe('Saved. Changes that affect every page are being published.');
  expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({
    ...SETTINGS, title: 'New blog', postsPerPage: 20, postsPerRss: 5, permalinkStructure: '/archives/%post_id%/',
    defaultCategory: 7, defaultCommentStatus: 'closed',
  });
  // the reset confirmation now asks for the saved title
  expect(view.getByLabelText('Type the site title (New blog) to confirm')).toBeTruthy();
});

test('typing a structure switches the preset to custom', async () => {
  const calls = fakeApi({ ...LOADED, 'PUT /settings': (body) => ({ body }) });
  const view = page();
  const structure = await view.findByLabelText('Permalink structure');
  await t.user.type(structure, 'x/');
  expect(input(view, 'Structure').value).toBe('custom');
  await t.user.selectOptions(view.getByLabelText('Structure'), 'Custom');
  await t.user.selectOptions(view.getByLabelText('Default post category'), 'Created on the first post (Uncategorized)');
  await t.user.click(view.getByRole('button', { name: 'Save settings' }));
  await view.findByRole('status');
  expect((calls.find((c) => c.method === 'PUT')?.body as SiteSettings).permalinkStructure).toBe('/%postname%/x/');
});

test('a rejected field is marked with its message', async () => {
  fakeApi({ ...LOADED, 'PUT /settings': { status: 400, body: { error: 'invalid', field: 'homeUrl' } } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Save settings' }));
  expect((await view.findByRole('alert')).textContent).toBe('Use a full address such as https://example.org, without a path.');
  expect(input(view, 'Site address').getAttribute('aria-invalid')).toBe('true');
});

test('a rejected default category is marked on its select', async () => {
  fakeApi({ ...LOADED, 'PUT /settings': { status: 400, body: { error: 'invalid', field: 'defaultCategory' } } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Save settings' }));
  expect(await view.findByText('Choose one of the categories.')).toBeTruthy();
  expect(view.getByLabelText('Default post category').getAttribute('aria-invalid')).toBe('true');
});

test('a save failure says so', async () => {
  fakeApi({ ...LOADED, 'PUT /settings': { status: 500 } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Save settings' }));
  expect((await view.findByRole('alert')).textContent).toBe('Could not save the settings.');
});

test('an expired session token says to reload', async () => {
  fakeApi({ ...LOADED, 'PUT /settings': { status: 400 } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Save settings' }));
  expect((await view.findByRole('alert')).textContent).toBe('Your session token has expired. Reload the page and try again.');
});

async function fillReset(view: ReturnType<typeof page>, confirm = 'My blog') {
  await t.user.type(await view.findByLabelText('Your password'), 'secret');
  await t.user.type(view.getByLabelText('Type the site title (My blog) to confirm'), confirm);
  await t.user.click(view.getByRole('button', { name: 'Reset site' }));
}

test('reset is enabled only with a password and the exact title', async () => {
  fakeApi(LOADED);
  const view = page();
  await t.user.type(await view.findByLabelText('Your password'), 'secret');
  await t.user.type(view.getByLabelText('Type the site title (My blog) to confirm'), 'My blo');
  expect((view.getByRole('button', { name: 'Reset site' }) as HTMLButtonElement).disabled).toBe(true);
  await t.user.type(view.getByLabelText('Type the site title (My blog) to confirm'), 'g');
  expect((view.getByRole('button', { name: 'Reset site' }) as HTMLButtonElement).disabled).toBe(false);
});

test('a reset sends the password and title and goes to the dashboard', async () => {
  const calls = fakeApi({ ...LOADED, 'POST /reset': { status: 204 } });
  const view = page();
  await fillReset(view);
  expect(await view.findByText('dashboard')).toBeTruthy();
  expect(calls.find((c) => c.path === '/reset')?.body).toEqual({ password: 'secret', confirm: 'My blog' });
});

test.each([
  [{ status: 401, body: { error: 'invalid_credentials' } }, 'The password is wrong.'],
  [{ status: 429, body: { error: 'rate_limited' } }, 'Too many wrong passwords. Wait a few minutes and try again.'],
  [{ status: 409, body: { error: 'import_running' } }, 'An import is running. Reset after it ends.'],
  [{ status: 500, body: { error: 'files' } }, 'The site was reset, but some files could not be removed. See the server log.'],
  [{ status: 400, body: { error: 'invalid', field: 'confirm' } }, 'Type the site title exactly.'],
  [{ status: 400 }, 'Your session token has expired. Reload the page and try again.'],
  [{ status: 500 }, 'Could not reset the site.'],
])('a reset failure %j says %s', async (reply, text) => {
  fakeApi({ ...LOADED, 'POST /reset': reply });
  const view = page();
  await fillReset(view);
  expect((await view.findByRole('alert')).textContent).toBe(text);
  expect((view.getByRole('button', { name: 'Reset site' }) as HTMLButtonElement).disabled).toBe(false);
});
