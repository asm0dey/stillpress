import { beforeAll, expect, test, vi } from 'vitest';
import type { ReusableBlock } from '../api.ts';
import { fakeApi, usePages } from '../testapi.ts';

// the provider keeps its blocks in a private sub-registry; on the default one the test reads and edits them through
// the store, since the canvas is an iframe jsdom never loads
vi.mock('@wordpress/block-editor', async (importOriginal) => {
  const orig = await importOriginal<typeof import('@wordpress/block-editor')>();
  const Provider = orig.BlockEditorProvider as unknown as (p: { useSubRegistry: boolean }) => React.JSX.Element;
  return { ...orig, BlockEditorProvider: (props: object) => <Provider {...props} useSubRegistry={false} /> };
});

const t = usePages();
let page: (state?: unknown) => ReturnType<typeof t.rtl.render>;
let editor: { getBlocks: () => { clientId: string; name: string; attributes: Record<string, unknown> }[] };
let edit: { updateBlockAttributes: (id: string, a: Record<string, unknown>) => void };
beforeAll(async () => {
  // Gutenberg probes these at load and render; jsdom has neither
  window.matchMedia ??= () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} }) as never;
  globalThis.ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} } as never;
  const { default: BlockEditor } = await import('./BlockEditor.tsx');
  const { select, dispatch } = await import('@wordpress/data');
  const { store } = await import('@wordpress/block-editor');
  editor = select(store) as never;
  edit = dispatch(store) as never;
  page = (state) => t.rtl.render(
    <t.router.MemoryRouter initialEntries={[{ pathname: '/blocks/3', state }]}>
      <t.router.Routes>
        <t.router.Route path="/blocks/:id" element={<BlockEditor />} />
        <t.router.Route path="/blocks/:id/revisions" element={<p>revisions screen</p>} />
        <t.router.Route path="/blocks" element={<p>blocks list</p>} />
      </t.router.Routes>
    </t.router.MemoryRouter>,
  );
});

const USER = { id: 1, login: 'ann', displayName: 'Ann', email: 'a@example.org', role: 'editor', locale: 'en', enrollRequired: false };
const SESSION = {
  'GET /session': { body: USER },
  'GET /site': { body: { timezone: 'UTC' } },
  'GET /blocks?content=true': { body: [] },
};
const PROMO = '<!-- wp:paragraph -->\n<p>Buy now</p>\n<!-- /wp:paragraph -->';
const BLOCK: ReusableBlock = {
  id: 3, title: 'Promo', content: PROMO, author: 'Ann', modifiedAt: '2026-01-01T00:00:00Z', usedBy: 0, canEdit: true,
};
const routes = (over: Record<string, unknown> = {}) => ({
  ...SESSION, 'GET /blocks/3': { body: BLOCK }, 'GET /blocks/3/revisions': { body: [] }, ...over,
}) as Parameters<typeof fakeApi>[0];

async function opened(state?: unknown) {
  const view = page(state);
  const title = (await view.findByLabelText('Title')) as HTMLInputElement;
  return { view, title };
}

test('opens the block: its title, and its content as blocks', async () => {
  fakeApi(routes());
  const { title } = await opened();
  expect(title.value).toBe('Promo');
  await t.rtl.waitFor(() => expect(editor.getBlocks().map((b) => [b.name, String(b.attributes.content)])).toEqual([['core/paragraph', 'Buy now']]));
});

test('saving sends the edited title and blocks with the version it started from', async () => {
  const calls = fakeApi(routes({
    'PUT /blocks/3': (body: unknown) => ({ body: { ...BLOCK, ...(body as object), modifiedAt: '2026-01-02T00:00:00Z', republished: 2 } }),
  }));
  const { view, title } = await opened();
  await t.rtl.waitFor(() => expect(editor.getBlocks()).toHaveLength(1));
  await t.user.clear(title);
  await t.user.type(title, 'Sale');
  await t.rtl.act(async () => { edit.updateBlockAttributes(editor.getBlocks()[0].clientId, { content: 'Buy later' }); });
  await t.user.click(view.getByRole('button', { name: 'Save' }));
  expect((await view.findByText(/Saved\./)).textContent).toBe('Saved. 2 items will be republished.');
  const put = calls.find((c) => c.method === 'PUT');
  expect(put?.body).toEqual({
    title: 'Sale', content: '<!-- wp:paragraph -->\n<p>Buy later</p>\n<!-- /wp:paragraph -->', expectedModifiedAt: '2026-01-01T00:00:00Z',
  });
});

test('an unchanged body is saved as the bytes it was opened with', async () => {
  const raw = '<!-- wp:paragraph --><p>Buy now</p><!-- /wp:paragraph -->';
  const calls = fakeApi(routes({ 'GET /blocks/3': { body: { ...BLOCK, content: raw } }, 'PUT /blocks/3': { body: { ...BLOCK, republished: 1 } } }));
  const { view, title } = await opened();
  await t.user.type(title, '!');
  await t.user.click(view.getByRole('button', { name: 'Save' }));
  expect(await view.findByText('Saved. 1 item will be republished.')).toBeTruthy();
  expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ title: 'Promo!', content: raw, expectedModifiedAt: BLOCK.modifiedAt });
});

test('a conflicting save says someone else changed the block', async () => {
  fakeApi(routes({ 'PUT /blocks/3': { status: 409, body: { error: 'conflict' } } }));
  const { view } = await opened();
  await t.user.click(view.getByRole('button', { name: 'Save' }));
  expect((await view.findByRole('alert')).textContent)
    .toContain('Someone else changed this block. Reload to see their version; your text stays in this tab.');
  await t.user.click(view.getByRole('button', { name: 'Dismiss' }));
  expect(view.queryByRole('alert')).toBeNull();
});

test('a missing block says it does not exist', async () => {
  fakeApi(routes({ 'GET /blocks/3': { status: 404, body: { error: 'not_found' } } }));
  expect((await page().findByRole('alert')).textContent).toBe('This block does not exist.');
});

test('any other load failure says the block could not be loaded', async () => {
  fakeApi(routes({ 'GET /blocks/3': { status: 500 } }));
  expect((await page().findByRole('alert')).textContent).toBe('Could not load this block.');
});

test('a failed session load says the editor could not be loaded', async () => {
  fakeApi({ 'GET /session': { status: 500 } });
  expect((await page().findByRole('alert')).textContent).toBe('Could not load the editor.');
});

test('a block the user cannot edit opens read-only, without Save or Revisions', async () => {
  fakeApi(routes({ 'GET /blocks/3': { body: { ...BLOCK, canEdit: false, autosave: { createdAt: '2026-01-03T00:00:00Z' } } }, 'GET /blocks/3/revisions': { body: [{}] } }));
  const { view, title } = await opened();
  expect(title.readOnly).toBe(true);
  expect(view.getByText(/You can view this reusable block but not change it/)).toBeTruthy();
  expect(view.queryByRole('button', { name: 'Save' })).toBeNull();
  expect(view.queryByText(/autosave of this block/)).toBeNull();
  await new Promise((r) => setTimeout(r, 50));
  expect(view.queryByRole('button', { name: /Revisions/ })).toBeNull();
});

test('with revisions, the Revisions button opens their screen', async () => {
  fakeApi(routes({ 'GET /blocks/3/revisions': { body: [{}, {}] } }));
  const { view } = await opened();
  await t.user.click(await view.findByRole('button', { name: 'Revisions (2)' }));
  expect(await view.findByText('revisions screen')).toBeTruthy();
});

test('the notice passed by the previous screen is shown', async () => {
  fakeApi(routes());
  const { view } = await opened({ notice: 'Restored the revision.' });
  expect(await view.findByText('Restored the revision.')).toBeTruthy();
});

test('a newer autosave can be loaded into the editor', async () => {
  fakeApi(routes({
    'GET /blocks/3': { body: { ...BLOCK, autosave: { createdAt: '2026-01-03T00:00:00Z' } } },
    'GET /blocks/3/autosave': { body: { title: 'Draft promo', content: '<!-- wp:paragraph --><p>Draft</p><!-- /wp:paragraph -->' } },
  }));
  const { view, title } = await opened();
  expect(view.getByText(/There is an autosave of this block/)).toBeTruthy();
  await t.user.click(view.getByRole('button', { name: 'View the autosave' }));
  await t.rtl.waitFor(() => expect(title.value).toBe('Draft promo'));
  await t.rtl.waitFor(() => expect(String(editor.getBlocks()[0]?.attributes.content)).toBe('Draft'));
  expect(view.queryByText(/There is an autosave of this block/)).toBeNull();
});

test('an autosave that cannot be loaded says so; dismissing the offer hides it', async () => {
  fakeApi(routes({ 'GET /blocks/3': { body: { ...BLOCK, autosave: { createdAt: '2026-01-03T00:00:00Z' } } } }));
  const { view } = await opened();
  await t.user.click(view.getByRole('button', { name: 'View the autosave' }));
  expect((await view.findByRole('alert')).textContent).toContain('Could not load the autosave.');
  const offer = view.getByText(/There is an autosave of this block/);
  await t.user.click(t.rtl.within(offer).getByRole('button', { name: 'Dismiss' }));
  expect(view.queryByText(/There is an autosave of this block/)).toBeNull();
});

test('leaving with unsaved edits asks first and stays when refused', async () => {
  fakeApi(routes());
  const { view, title } = await opened();
  await t.user.type(title, '!');
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  await t.user.click(view.getByRole('link', { name: /Reusable blocks/ }));
  expect(confirm).toHaveBeenCalledWith('Leave without saving your changes?');
  expect(view.queryByText('blocks list')).toBeNull();
  confirm.mockReturnValue(true);
  await t.user.click(view.getByRole('link', { name: /Reusable blocks/ }));
  expect(await view.findByText('blocks list')).toBeTruthy();
  confirm.mockRestore();
});
