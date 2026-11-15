import { beforeAll, expect, test, vi } from 'vitest';
import type { Item, TermView } from '../api.ts';
import { type Call, fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: (state?: unknown) => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  // jsdom lacks these; Gutenberg's components and TinyMCE probe them
  window.matchMedia ??= () => ({ matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} }) as never;
  globalThis.ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} } as never;
  const { default: Editor } = await import('./Editor.tsx');
  const { MemoryRouter, Route, Routes } = t.router;
  page = (state) => t.rtl.render(
    <MemoryRouter initialEntries={[{ pathname: '/edit/5', state }]}>
      <Routes>
        <Route path="/edit/:id" element={<Editor />} />
        <Route path="/edit/:id/revisions" element={<p>revisions screen</p>} />
      </Routes>
    </MemoryRouter>,
  );
});

const POST: Item = {
  id: 5, type: 'post', title: 'Hello', slug: 'hello', status: 'draft', body: '<!-- wp:paragraph -->\n<p>Hi</p>\n<!-- /wp:paragraph -->',
  bodyFormat: 'blocks', excerpt: '', date: null, permalink: null, categories: [], tags: [2], authorId: 1, author: 'ada', parentId: null,
  commentStatus: 'open', menuOrder: null, modifiedAt: '2026-11-01T10:00:00Z', autosave: null, canEdit: true,
};
const PAGE: Item = { ...POST, type: 'page', title: 'About', slug: 'about', tags: [], parentId: 3, menuOrder: 2 };
const CATS: TermView[] = [{ id: 10, name: 'News', slug: 'news', parentId: null }];
const TAGS: TermView[] = [{ id: 2, name: 'java', slug: 'java', parentId: null }];
const ADA = { id: 1, login: 'ada', displayName: 'Ada', email: 'a@example.org', role: 'administrator', hasPassword: true, twoFactor: false };
const BOB = { ...ADA, id: 2, login: 'bob', displayName: 'Bob', role: 'author' };

const routes = (role = 'administrator', item: Item = POST) => ({
  'GET /session': { body: { id: 1, login: 'ada', role } },
  'GET /site': { body: { timezone: 'UTC' } },
  'GET /items/5': { body: item },
  'GET /terms?taxonomy=category': { body: CATS },
  'GET /terms?taxonomy=post_tag': { body: TAGS },
  'GET /users': { body: [ADA, BOB] },
  'GET /items/5/revisions': { body: [] },
  // the reply mirrors what was saved, as the server would
  'PUT /items/5': (b: unknown) => ({ body: { ...item, ...(b as object), modifiedAt: '2026-11-02T10:00:00Z' } }),
});
const put = (calls: Call[]) => calls.filter((c) => c.method === 'PUT' && c.path === '/items/5').at(-1)?.body as Record<string, unknown>;

async function open(r: Record<string, unknown> = routes(), state?: unknown) {
  const calls = fakeApi(r as never);
  const view = page(state);
  await view.findByLabelText('Title');
  return { calls, view };
}

test('loads the post with its title, sidebar and no save needed', async () => {
  const { view } = await open();
  expect((view.getByLabelText('Title') as HTMLInputElement).value).toBe('Hello');
  expect((view.getByLabelText('Slug') as HTMLInputElement).value).toBe('hello');
  expect(view.getByText('java')).toBeTruthy();
  expect(view.getByRole('button', { name: 'Publish' })).toBeTruthy();
  expect(view.getByRole('button', { name: 'Save draft' })).toBeTruthy();
});

test('the editor says so when the session cannot be loaded', async () => {
  fakeApi({ 'GET /session': { status: 500 } });
  expect((await page().findByRole('alert')).textContent).toBe('Could not load the editor.');
});

test('a refused item says why', async () => {
  fakeApi({ ...routes(), 'GET /items/5': { status: 403, body: { error: 'forbidden' } } });
  expect((await page().findByRole('alert')).textContent).toBe('You cannot edit this item.');
  fakeApi({ ...routes(), 'GET /items/5': { status: 500 } });
  t.rtl.cleanup();
  expect((await page().findByRole('alert')).textContent).toBe('Could not load this item.');
});

test('saving a draft sends the whole post and confirms', async () => {
  const { calls, view } = await open();
  const title = view.getByLabelText('Title');
  await t.user.clear(title);
  await t.user.type(title, 'Bye');
  await t.user.click(view.getByRole('button', { name: 'Save draft' }));
  expect((await view.findByText('Draft saved.')).getAttribute('role')).toBe('status');
  expect(put(calls)).toEqual({
    title: 'Bye', slug: 'hello', body: POST.body, bodyFormat: 'blocks', excerpt: '', status: 'draft', categories: [], tags: [2],
    authorId: 1, commentStatus: 'open', expectedModifiedAt: '2026-11-01T10:00:00Z',
  });
  // the save made a revision
  await t.user.click(await view.findByRole('button', { name: 'Revisions (1)' }));
  expect(await view.findByText('revisions screen')).toBeTruthy();
});

test('publishing sends the sidebar edits: slug, excerpt, comments, author, category and a new tag', async () => {
  const r = { ...routes(), 'POST /terms': { status: 201, body: { id: 7, name: 'quarkus', slug: 'quarkus', parentId: null } } };
  const { calls, view } = await open(r);
  const slug = view.getByLabelText('Slug');
  await t.user.clear(slug);
  await t.user.type(slug, 'hi-there');
  await t.user.click(view.getByRole('checkbox', { name: 'News' }));
  await t.user.type(view.getByLabelText('Add tag'), 'quarkus{Enter}');
  await t.user.type(view.getByLabelText('Write an excerpt (optional)'), 'Short');
  await t.user.selectOptions(view.getByLabelText('Author'), '2');
  await t.user.selectOptions(view.getByLabelText('Comments'), 'closed');
  await t.user.click(view.getByRole('button', { name: 'Publish' }));
  expect(await view.findByText('Published. The site updates in a few seconds.')).toBeTruthy();
  expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ taxonomy: 'post_tag', name: 'quarkus', parentId: null });
  expect(put(calls)).toMatchObject({
    slug: 'hi-there', excerpt: 'Short', status: 'publish', categories: [10], tags: [2, 7], authorId: 2, commentStatus: 'closed',
  });
  // now published: Update replaces Publish, and there is no draft to save
  expect(view.getByRole('button', { name: 'Update' })).toBeTruthy();
  expect(view.queryByRole('button', { name: 'Save draft' })).toBeNull();
});

test('private visibility saves as private', async () => {
  const { calls, view } = await open();
  await t.user.selectOptions(view.getByLabelText('Visibility'), 'private');
  await t.user.click(view.getByRole('button', { name: 'Publish' }));
  expect(await view.findByText('Saved as private.')).toBeTruthy();
  expect(put(calls).status).toBe('private');
});

test('a published post with a future date schedules', async () => {
  const { calls, view } = await open(routes('administrator', { ...POST, status: 'publish', date: '2099-01-01T10:00:00' }));
  await t.user.click(view.getByRole('button', { name: 'Schedule' }));
  await view.findByText('Published. The site updates in a few seconds.');
  // the date was not changed in the sidebar, so it is not sent
  expect(put(calls).status).toBe('publish');
  expect(put(calls)).not.toHaveProperty('localDate');
});

test('a contributor submits for review and cannot set the author', async () => {
  const r = { ...routes('contributor'), 'PUT /items/5': (b: unknown) => ({ body: { ...POST, ...(b as object), status: 'pending' } }) };
  const { calls, view } = await open(r);
  expect(view.queryByRole('button', { name: 'Publish' })).toBeNull();
  await t.user.click(view.getByRole('button', { name: 'Submit for review' }));
  expect(await view.findByText('Submitted for review.')).toBeTruthy();
  expect(put(calls)).toMatchObject({ status: 'pending', tags: [2] });
  expect(put(calls)).not.toHaveProperty('authorId');
  expect(calls.some((c) => c.path === '/users')).toBe(false);
});

test('a page sends its parent and order; no parent is sent as 0', async () => {
  const r = {
    ...routes('administrator', PAGE),
    'GET /items?type=page&page=1': { body: { items: [{ id: 3, title: 'Home' }, { id: 5, title: 'About' }], total: 3 } },
    'GET /items?type=page&page=2': { body: { items: [{ id: 4, title: '' }], total: 3 } },
  };
  const { calls, view } = await open(r);
  expect(view.getByRole('button', { name: 'Publish' })).toBeTruthy();
  const parent = view.getByLabelText('Parent page') as HTMLSelectElement;
  // the page itself is not offered as its own parent
  await t.rtl.waitFor(() => expect([...parent.options].map((o) => o.text)).toEqual(['(no parent)', 'Home', '(no title) #4']));
  expect(parent.value).toBe('3');
  await t.user.selectOptions(parent, '');
  const order = view.getByLabelText('Order');
  await t.user.clear(order);
  await t.user.type(order, '9');
  await t.user.click(view.getByRole('button', { name: 'Save draft' }));
  await view.findByText('Draft saved.');
  expect(put(calls)).toMatchObject({ parentId: 0, menuOrder: 9 });
  expect(Object.keys(put(calls))).not.toContain('tags');
});

test('save refusals are shown, with the unsafe HTML listed', async () => {
  let reply = { status: 409, body: { error: 'conflict' } as unknown };
  const { view } = await open({ ...routes(), 'PUT /items/5': () => reply });
  await t.user.click(view.getByRole('button', { name: 'Save draft' }));
  expect((await view.findByRole('alert')).textContent).toContain('Someone else changed this post.');
  await t.user.click(view.getByRole('button', { name: 'Dismiss' }));
  expect(view.queryByRole('alert')).toBeNull();
  reply = { status: 400, body: { error: 'unsafe_html', field: 'body', violations: ['<script> element'] } };
  await t.user.click(view.getByRole('button', { name: 'Save draft' }));
  const alert = await view.findByRole('alert');
  expect(alert.textContent).toContain('This post contains HTML your role cannot publish:');
  expect(alert.querySelector('li')?.textContent).toBe('<script> element');
});

test('adding a category creates it and ticks it', async () => {
  const r = { ...routes(), 'POST /terms': { status: 201, body: { id: 11, name: 'Tech', slug: 'tech', parentId: 10 } } };
  const { calls, view } = await open(r);
  await t.user.click(view.getByRole('button', { name: 'Add new category' }));
  await t.user.type(view.getByLabelText('New category name'), 'Tech');
  await t.user.selectOptions(view.getByLabelText('Parent category'), '10');
  await t.user.click(view.getByRole('button', { name: 'Add new category' }));
  expect(((await view.findByRole('checkbox', { name: 'Tech' })) as HTMLInputElement).checked).toBe(true);
  expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ taxonomy: 'category', name: 'Tech', parentId: 10 });
  await t.user.click(view.getByRole('button', { name: 'Save draft' }));
  await view.findByText('Draft saved.');
  expect(put(calls).categories).toEqual([11]);
});

test('a category that cannot be added says so', async () => {
  const { view } = await open({ ...routes(), 'POST /terms': { status: 500 } });
  await t.user.click(view.getByRole('button', { name: 'Add new category' }));
  await t.user.type(view.getByLabelText('New category name'), 'Tech');
  await t.user.click(view.getByRole('button', { name: 'Add new category' }));
  expect((await view.findByRole('alert')).textContent).toContain('Could not add the category.');
});

test('the notice the previous screen passed is shown', async () => {
  const { view } = await open(routes(), { notice: 'Post created.' });
  expect((await view.findByText('Post created.')).getAttribute('role')).toBe('status');
});

test('a newer autosave is offered and loads into the editor', async () => {
  const r = {
    ...routes('administrator', { ...POST, autosave: { createdAt: '2026-11-02T10:00:00Z' } }),
    'GET /items/5/autosave': { body: { title: 'Autosaved', body: POST.body, excerpt: 'From autosave' } },
  };
  const { view } = await open(r);
  await t.user.click(view.getByRole('button', { name: 'View the autosave' }));
  await t.rtl.waitFor(() => expect((view.getByLabelText('Title') as HTMLInputElement).value).toBe('Autosaved'));
  expect(view.queryByText(/There is an autosave/)).toBeNull();
});

test('preview autosaves an edited post, then opens it', async () => {
  const tab = { close: vi.fn(), location: { href: '' } };
  const openTab = vi.spyOn(window, 'open').mockReturnValue(tab as never);
  try {
    const { calls, view } = await open({ ...routes(), 'PUT /items/5/autosave': { status: 204 } });
    await t.user.type(view.getByLabelText('Title'), '!');
    await t.user.click(view.getByRole('button', { name: 'Preview' }));
    await t.rtl.waitFor(() => expect(tab.location.href).toBe('/admin/preview/5'));
    expect(openTab).toHaveBeenCalledWith('about:blank', '_blank');
    expect(calls.find((c) => c.path === '/items/5/autosave')?.body).toEqual({ title: 'Hello!', body: POST.body, excerpt: '' });
  } finally { openTab.mockRestore(); }
});

test('preview refused for unsafe HTML closes the tab and lists it', async () => {
  const tab = { close: vi.fn(), location: { href: '' } };
  const openTab = vi.spyOn(window, 'open').mockReturnValue(tab as never);
  try {
    const { view } = await open({
      ...routes(), 'PUT /items/5/autosave': { status: 400, body: { error: 'unsafe_html', violations: ['onclick attribute'] } },
    });
    await t.user.type(view.getByLabelText('Title'), '!');
    await t.user.click(view.getByRole('button', { name: 'Preview' }));
    const alert = await view.findByRole('alert');
    expect(alert.textContent).toContain('This post contains HTML your role cannot publish:');
    expect(alert.textContent).toContain('onclick attribute');
    expect(tab.close).toHaveBeenCalled();
    expect(tab.location.href).toBe('');
  } finally { openTab.mockRestore(); }
});

test('a classic post opens in the Classic block and is saved as classic', async () => {
  const classic = { ...POST, body: '<p>Old <b>post</b></p>', bodyFormat: 'classic' as const };
  const { calls, view } = await open(routes('administrator', classic));
  await t.user.click(view.getByRole('button', { name: 'Save draft' }));
  await view.findByText('Draft saved.');
  expect(put(calls)).toMatchObject({ body: '<p>Old <b>post</b></p>', bodyFormat: 'classic' });
});
