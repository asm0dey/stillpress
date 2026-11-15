import { beforeAll, expect, test, vi } from 'vitest';
import type { TermView } from '../api.ts';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: (taxonomy?: 'category' | 'post_tag') => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Terms } = await import('./Terms.tsx');
  page = (taxonomy = 'category') =>
    t.rtl.render(<t.router.MemoryRouter initialEntries={['/terms']}><Terms taxonomy={taxonomy} /></t.router.MemoryRouter>);
});

const NEWS: TermView = { id: 1, name: 'News', slug: 'news', parentId: null, description: 'All news', count: 3 };
const LOCAL: TermView = { id: 2, name: 'Local', slug: 'local', parentId: 1 };
const SPORT: TermView = { id: 3, name: 'Sport', slug: 'sport', parentId: null, count: 1 };
const SESSION = { 'GET /session': { body: { role: 'editor' } }, 'GET /site': { body: { importAllowed: false } } };
const CATEGORIES = { ...SESSION, 'GET /terms?taxonomy=category': { body: [LOCAL, NEWS, SPORT] } };

const rows = (view: ReturnType<typeof page>) =>
  view.getAllByRole('row').slice(1).map((r) => [...r.querySelectorAll('td')].slice(0, 3).map((c) => c.textContent));

test('lists categories as a tree, children under their parent', async () => {
  fakeApi(CATEGORIES);
  const view = page();
  expect(await view.findByRole('heading', { name: 'Categories' })).toBeTruthy();
  await view.findByText('Local');
  expect(rows(view)).toEqual([['News', 'news', '3'], ['Local', 'local', '0'], ['Sport', 'sport', '1']]);
  const options = [...(view.getByLabelText('Parent') as HTMLSelectElement).options].map((o) => o.textContent);
  expect(options).toEqual(['None', 'News', '— Local', 'Sport']);
});

test('lists tags flat, without a parent', async () => {
  fakeApi({ ...SESSION, 'GET /terms?taxonomy=post_tag': { body: [{ ...LOCAL, parentId: null }, SPORT] } });
  const view = page('post_tag');
  await view.findByText('Local');
  expect(view.getByRole('heading', { name: 'Tags' })).toBeTruthy();
  expect(rows(view).map((r) => r[0])).toEqual(['Local', 'Sport']);
  expect(view.queryByLabelText('Parent')).toBeNull();
  expect(view.getByRole('button', { name: 'Add tag' })).toBeTruthy();
});

test('says so when the list cannot be loaded', async () => {
  fakeApi({ ...SESSION, 'GET /terms?taxonomy=category': { status: 500 } });
  expect((await page().findByRole('alert')).textContent).toBe('Could not load the list.');
});

test('adding a category sends it with its parent and clears the form', async () => {
  const calls = fakeApi({ ...CATEGORIES, 'POST /terms': { status: 201, body: { id: 4 } } });
  const view = page();
  await view.findByText('Local');
  const name = view.getByLabelText('Name');
  await t.user.type(name, 'Weather');
  await t.user.selectOptions(view.getByLabelText('Parent'), '1');
  await t.user.click(view.getByRole('button', { name: 'Add category' }));
  await t.rtl.waitFor(() => expect(calls.filter((c) => c.path === '/terms?taxonomy=category')).toHaveLength(2));
  expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ taxonomy: 'category', name: 'Weather', parentId: 1 });
  expect((name as HTMLInputElement).value).toBe('');
  expect((view.getByLabelText('Parent') as HTMLSelectElement).value).toBe('');
});

test('a missing name marks the name field', async () => {
  fakeApi({ ...CATEGORIES, 'POST /terms': { status: 400, body: { error: 'invalid', field: 'name' } } });
  const view = page();
  await view.findByText('Local');
  await t.user.type(view.getByLabelText('Name'), ' ');
  await t.user.click(view.getByRole('button', { name: 'Add category' }));
  expect((await view.findByRole('alert')).textContent).toBe('Enter a name.');
  expect(view.getByLabelText('Name').getAttribute('aria-invalid')).toBe('true');
});

test('an author may not add one', async () => {
  fakeApi({ ...CATEGORIES, 'POST /terms': { status: 403 } });
  const view = page();
  await view.findByText('Local');
  await t.user.type(view.getByLabelText('Name'), 'X');
  await t.user.click(view.getByRole('button', { name: 'Add category' }));
  expect((await view.findByRole('alert')).textContent).toBe('Only editors and administrators can change this.');
});

test('an expired session token says to reload', async () => {
  fakeApi({ ...CATEGORIES, 'POST /terms': { status: 400 } });
  const view = page();
  await view.findByText('Local');
  await t.user.type(view.getByLabelText('Name'), 'X');
  await t.user.click(view.getByRole('button', { name: 'Add category' }));
  expect((await view.findByRole('alert')).textContent).toBe('Your session token has expired. Reload the page and try again.');
});

test('any other failure says the change was not saved', async () => {
  fakeApi({ ...CATEGORIES, 'POST /terms': { status: 500 } });
  const view = page();
  await view.findByText('Local');
  await t.user.type(view.getByLabelText('Name'), 'X');
  await t.user.click(view.getByRole('button', { name: 'Add category' }));
  expect((await view.findByRole('alert')).textContent).toBe('Could not save the change.');
});

test('editing a category sends the draft and closes the form', async () => {
  const calls = fakeApi({ ...CATEGORIES, 'PUT /terms/2': { status: 204 } });
  const view = page();
  await view.findByText('Local');
  await t.user.click(view.getAllByRole('button', { name: 'Edit' })[1]);
  const editParent = view.getAllByLabelText('Parent')[0] as HTMLSelectElement;
  // a category cannot be offered as its own parent
  expect([...editParent.options].map((o) => o.textContent)).toEqual(['None', 'News', 'Sport']);
  expect(editParent.value).toBe('1');
  const name = view.getByDisplayValue('Local');
  await t.user.clear(name);
  await t.user.type(name, 'Local news');
  await t.user.type(view.getByDisplayValue('local'), '-news');
  await t.user.selectOptions(editParent, '3');
  await t.user.type(view.getByLabelText('Description'), 'Nearby');
  await t.user.click(view.getByRole('button', { name: 'Save' }));
  await t.rtl.waitFor(() => expect(view.queryByRole('button', { name: 'Save' })).toBeNull());
  expect(calls.find((c) => c.method === 'PUT')?.body)
    .toEqual({ name: 'Local news', slug: 'local-news', parentId: 3, description: 'Nearby' });
  expect(calls.filter((c) => c.path === '/terms?taxonomy=category')).toHaveLength(2);
});

test('editing a tag shows its description and no parent', async () => {
  fakeApi({ ...SESSION, 'GET /terms?taxonomy=post_tag': { body: [NEWS] } });
  const view = page('post_tag');
  await t.user.click(await view.findByRole('button', { name: 'Edit' }));
  expect((view.getByLabelText('Description') as HTMLInputElement).value).toBe('All news');
  expect(view.queryByLabelText('Parent')).toBeNull();
  await t.user.click(view.getByRole('button', { name: 'Cancel' }));
  expect(view.queryByRole('button', { name: 'Save' })).toBeNull();
});

test('a taken slug marks the slug field', async () => {
  fakeApi({ ...CATEGORIES, 'PUT /terms/3': { status: 409, body: { error: 'slug_taken' } } });
  const view = page();
  await view.findByText('Local');
  await t.user.click(view.getAllByRole('button', { name: 'Edit' })[2]);
  await t.user.click(view.getByRole('button', { name: 'Save' }));
  expect((await view.findByRole('alert')).textContent).toBe('This slug is already used, or has no letters or digits.');
  expect(view.getByDisplayValue('sport').getAttribute('aria-invalid')).toBe('true');
});

test('a parent loop marks the parent select', async () => {
  fakeApi({ ...CATEGORIES, 'PUT /terms/1': { status: 400, body: { error: 'invalid', field: 'parentId' } } });
  const view = page();
  await view.findByText('Local');
  await t.user.click(view.getAllByRole('button', { name: 'Edit' })[0]);
  await t.user.selectOptions(view.getAllByLabelText('Parent')[0], '2');
  await t.user.click(view.getByRole('button', { name: 'Save' }));
  // shortcut: the error also shows under the add form's parent select (Terms.tsx does not gate it on editing)
  expect((await view.findAllByText('A category cannot sit under itself or one of its own subcategories.'))[0].id).toBe('edit-parent-error');
  expect(view.getAllByLabelText('Parent')[0].getAttribute('aria-invalid')).toBe('true');
});

test('deleting asks first and reloads the list', async () => {
  const calls = fakeApi({ ...CATEGORIES, 'DELETE /terms/3': { status: 204 } });
  const confirm = vi.fn(() => true);
  vi.stubGlobal('confirm', confirm);
  const view = page();
  await view.findByText('Local');
  await t.user.click(view.getAllByRole('button', { name: 'Delete' })[2]);
  await t.rtl.waitFor(() => expect(calls.filter((c) => c.path === '/terms?taxonomy=category')).toHaveLength(2));
  expect(confirm).toHaveBeenCalledWith('Delete “Sport”? Its posts lose it; addresses under it stop working.');
  expect(calls.some((c) => c.method === 'DELETE' && c.path === '/terms/3')).toBe(true);
});

test('a declined delete sends nothing', async () => {
  const calls = fakeApi(CATEGORIES);
  vi.stubGlobal('confirm', () => false);
  const view = page();
  await view.findByText('Local');
  await t.user.click(view.getAllByRole('button', { name: 'Delete' })[0]);
  expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
});

test('the default category cannot be deleted', async () => {
  fakeApi({ ...CATEGORIES, 'DELETE /terms/1': { status: 409, body: { error: 'default_category' } } });
  vi.stubGlobal('confirm', () => true);
  const view = page();
  await view.findByText('Local');
  await t.user.click(view.getAllByRole('button', { name: 'Delete' })[0]);
  expect((await view.findByRole('alert')).textContent)
    .toBe('The default category cannot be deleted. Choose another one in Settings first.');
});
