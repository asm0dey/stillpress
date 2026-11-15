import { beforeAll, expect, test } from 'vitest';
import type { ImportStatus } from '../api.ts';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Import } = await import('./Import.tsx');
  const { MemoryRouter, Route, Routes } = t.router;
  page = () => t.rtl.render(
    <MemoryRouter initialEntries={['/import']}>
      <Routes>
        <Route path="/import" element={<Import />} />
        <Route path="/" element={<p>dashboard</p>} />
      </Routes>
    </MemoryRouter>,
  );
});

const IDLE: ImportStatus = { state: 'idle', items: 0, media: 0, report: null, error: null, contentLeft: false };
const SESSION = { 'GET /session': { body: { role: 'administrator' } }, 'GET /site': { body: { importAllowed: true } } };
const status = (s: Partial<ImportStatus>) => ({ ...SESSION, 'GET /import': { body: { ...IDLE, ...s } } });
const XML = new File(['<rss/>'], 'export.xml', { type: 'text/xml' });
// user-event's upload sets files, but jsdom still counts the required file input as empty and blocks a click
const submit = (view: ReturnType<typeof page>) => t.rtl.fireEvent.submit(view.getByRole('button', { name: 'Import' }).closest('form')!);

test('says so when the status cannot be loaded', async () => {
  fakeApi({ ...SESSION, 'GET /import': { status: 500 } });
  expect((await page().findByRole('alert')).textContent).toBe('Could not load the import status.');
});

test('a closed import goes to the dashboard', async () => {
  fakeApi(status({ state: 'closed' }));
  expect(await page().findByText('dashboard')).toBeTruthy();
});

test('uploads the file with the settings and follows the import until done', async () => {
  // the first GET is the page's load, the next ones the polls after the upload
  const replies: Partial<ImportStatus>[] = [{}, { state: 'running', items: 7, media: 2 }, { state: 'done', items: 9, media: 2, report: '9 items imported' }];
  const calls = fakeApi({
    ...SESSION,
    'GET /import': () => ({ body: { ...IDLE, ...(replies.shift() ?? { state: 'done' }) } }),
    'POST /import': { status: 202, body: { ...IDLE, state: 'running', items: 1, media: 0 } },
  });
  const view = page();
  await t.user.upload(await view.findByLabelText('Export file (.xml)'), XML);
  for (const [label, value] of [['Posts per page', '5'], ['Items per feed', '15'], ['Category base', 'topics'], ['Tag base', 'labels']]) {
    await t.user.clear(view.getByLabelText(label));
    await t.user.type(view.getByLabelText(label), value);
  }
  submit(view);
  expect((await view.findByRole('status')).textContent).toBe('Importing… 1 posts, pages and attachments, 0 media files so far.');
  expect(await view.findByText('Importing… 7 posts, pages and attachments, 2 media files so far.', {}, { timeout: 2000 })).toBeTruthy();
  expect(await view.findByText('9 items imported', {}, { timeout: 2000 })).toBeTruthy();
  expect(view.getByRole('status').textContent).toContain('The import is done. The site is publishing in the background.');
  expect(view.getByRole('link', { name: 'Follow its progress on the dashboard' }).getAttribute('href')).toBe('/');
  const sent = calls.find((c) => c.method === 'POST')?.body as FormData;
  expect((sent.get('file') as File).name).toBe('export.xml');
  expect(Object.fromEntries([...sent.entries()].filter(([k]) => k !== 'file')))
    .toEqual({ postsPerPage: '5', postsPerRss: '15', categoryBase: 'topics', tagBase: 'labels' });
}, 10000);

test('import without a file asks for one', async () => {
  const calls = fakeApi(status({}));
  const view = page();
  await view.findByLabelText('Export file (.xml)');
  // with no file the browser's own required check would stop a click, so this submits past it
  submit(view);
  expect((await view.findByRole('alert')).textContent).toBe('Choose the export file.');
  expect(calls.some((c) => c.method === 'POST')).toBe(false);
});

test.each([
  [{ status: 409, body: { error: 'imported', message: 'Already imported here.' } }, 'Already imported here.'],
  [{ status: 409, body: { error: 'imported' } }, 'This site has already been imported.'],
  [{ status: 400, body: { error: 'Posts per page must be a number.' } }, 'Posts per page must be a number.'],
  [{ status: 400, body: { field: 'postsPerPage' } }, 'Check the settings.'],
  [{ status: 400 }, 'Your session token has expired. Reload the page and try again.'],
  [{ status: 500 }, 'The upload failed. Try again.'],
])('an upload answered %j says %s', async (reply, text) => {
  fakeApi({ ...status({}), 'POST /import': reply });
  const view = page();
  await t.user.upload(await view.findByLabelText('Export file (.xml)'), XML);
  submit(view);
  expect((await view.findByRole('alert')).textContent).toBe(text);
  expect((view.getByRole('button', { name: 'Import' }) as HTMLButtonElement).disabled).toBe(false);
});

test('a stalled import shows its error', async () => {
  fakeApi(status({ state: 'running', error: 'The import runs in another process.' }));
  const view = page();
  expect((await view.findByRole('alert')).textContent).toBe('The import runs in another process.');
  expect(view.queryByRole('status')).toBeNull();
});

test('a failure before any content lets the user try again', async () => {
  fakeApi(status({ state: 'failed', error: 'Not a WordPress export.', report: 'line 1' }));
  const view = page();
  const alert = await view.findByRole('alert');
  expect(alert.textContent).toContain('The import failed: Not a WordPress export.');
  expect(alert.textContent).toContain('Nothing was imported. Fix the file and try again.');
  expect(view.getByText('line 1')).toBeTruthy();
  expect(view.getByLabelText('Export file (.xml)')).toBeTruthy();
});

test('a failure after content was written points to Reset site', async () => {
  fakeApi(status({ state: 'failed', contentLeft: true }));
  const view = page();
  const alert = await view.findByRole('alert');
  expect(alert.textContent).toContain('The import failed: see the server log.');
  expect(alert.textContent).toContain('Content was already written. To start over, use Reset site at the bottom of Settings.');
  expect(view.getByRole('link', { name: 'Open Settings' }).getAttribute('href')).toBe('/settings');
  expect(view.queryByLabelText('Export file (.xml)')).toBeNull();
});

test('a site with content cannot import', async () => {
  fakeApi(status({ contentLeft: true }));
  const view = page();
  expect((await view.findByRole('status')).textContent).toContain('This site already has content, and import works only into an empty site.');
  expect(view.getByRole('link', { name: 'Open Settings' })).toBeTruthy();
  expect(view.queryByLabelText('Export file (.xml)')).toBeNull();
});
