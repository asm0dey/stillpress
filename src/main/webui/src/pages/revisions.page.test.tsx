/// <reference types="bun" />
import { afterAll, afterEach, beforeAll, expect, test } from 'bun:test';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: (path: string) => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Revisions } = await import('./Revisions.tsx');
  const { Route, Routes, MemoryRouter, useLocation } = t.router;
  // where restore lands: the editor, with the notice it was handed
  const Landed = () => <p>landed: {(useLocation().state as { notice?: string } | null)?.notice}</p>;
  page = (path) => t.rtl.render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/edit/:id/revisions" element={<Revisions kind="items" />} />
        <Route path="/blocks/:id/revisions" element={<Revisions kind="blocks" />} />
        <Route path="/edit/:id" element={<Landed />} />
        <Route path="/blocks/:id" element={<Landed />} />
      </Routes>
    </MemoryRouter>,
  );
});

// Bun's own confirm reads the terminal
const realConfirm = globalThis.confirm;
let confirmed = true;
beforeAll(() => { globalThis.confirm = () => confirmed; });
afterEach(() => { confirmed = true; });
afterAll(() => { globalThis.confirm = realConfirm; });

const SHELL = {
  'GET /session': { body: { role: 'editor' } },
  'GET /site': { body: { importAllowed: false, timezone: 'UTC' } },
};
const LIST = [
  { id: 12, author: 'ada', createdAt: '2026-11-03T10:00:00Z' },
  { id: 11, author: null, createdAt: '2026-11-02T10:00:00Z' },
  { id: 10, author: 'bob', createdAt: '2026-11-01T10:00:00Z' },
];
const rev = (id: number, title: string, body: string) => ({ id, author: 'ada', createdAt: '2026-11-03T10:00:00Z', title, body });
const ITEM = {
  ...SHELL,
  'GET /items/7/revisions': { body: LIST },
  'GET /items/7': { body: { canEdit: true } },
  'GET /revisions/12': { body: rev(12, 'New title', 'a\nc\n') },
  'GET /revisions/11': { body: rev(11, 'Old title', 'a\nb\n') },
  'GET /revisions/10': { body: rev(10, 'First title', 'a\n') },
};

const side = (view: ReturnType<typeof page>, name: 'From' | 'To') => view.getByRole('region', { name });
const marked = (el: HTMLElement, cls: string) => [...el.querySelectorAll(`.${cls}`)].map((e) => e.textContent);

test('compares the one before the newest with the newest', async () => {
  fakeApi(ITEM);
  const view = page('/edit/7/revisions');
  await view.findByRole('region', { name: 'From' });
  expect(marked(side(view, 'From'), 'diff-removed')).toEqual(['−Removed: Old', '−Removed: b']);
  expect(marked(side(view, 'To'), 'diff-added')).toEqual(['+Added: New', '+Added: c']);
  expect(view.getByText(/by unknown$/)).toBeTruthy();
  expect((view.getByLabelText(/^Compare to .* by ada$/) as HTMLInputElement).checked).toBe(true);
});

test('picking another revision compares with it', async () => {
  fakeApi(ITEM);
  const view = page('/edit/7/revisions');
  await t.user.click(await view.findByLabelText(/^Compare from .* by bob$/));
  await t.rtl.waitFor(() => expect(side(view, 'From').querySelector('h3')?.textContent).toContain('First title'));
  expect(marked(side(view, 'To'), 'diff-added')).toEqual(['+Added: New', '+Added: c']);
});

test('a revision that cannot be loaded says so', async () => {
  fakeApi({ ...ITEM, 'GET /revisions/10': { status: 500 } });
  const view = page('/edit/7/revisions');
  await t.user.click(await view.findByLabelText(/^Compare to .* by bob$/));
  expect((await view.findByRole('alert')).textContent).toBe('Could not load that revision.');
});

test('no revisions yet', async () => {
  fakeApi({ ...SHELL, 'GET /items/7/revisions': { body: [] }, 'GET /items/7': { body: { canEdit: true } } });
  expect(await page('/edit/7/revisions').findByText('No revisions yet.')).toBeTruthy();
});

test('a user who may not see them is told so', async () => {
  fakeApi({ ...ITEM, 'GET /items/7/revisions': { status: 403 } });
  expect((await page('/edit/7/revisions').findByRole('alert')).textContent).toBe('You cannot see these revisions.');
});

test('any other failure says the revisions could not be loaded', async () => {
  fakeApi({ ...ITEM, 'GET /items/7/revisions': { status: 500 } });
  expect((await page('/edit/7/revisions').findByRole('alert')).textContent).toBe('Could not load the revisions.');
});

test('restore is offered only to who may edit', async () => {
  fakeApi({ ...ITEM, 'GET /items/7': { body: { canEdit: false } } });
  const view = page('/edit/7/revisions');
  await view.findByRole('region', { name: 'From' });
  expect(view.queryByRole('button', { name: /^Restore revision/ })).toBeNull();
});

test('restoring a published post goes back to the editor with a notice', async () => {
  const calls = fakeApi({ ...ITEM, 'POST /items/7/revisions/11/restore': { body: { status: 'publish' } } });
  const view = page('/edit/7/revisions');
  const buttons = await view.findAllByRole('button', { name: /^Restore revision from / });
  await t.user.click(buttons[1]);
  expect((await view.findByText(/^landed/)).textContent).toBe('landed: Restored. The site updates in a few seconds.');
  expect(calls.filter((c) => c.method === 'POST').map((c) => c.path)).toEqual(['/items/7/revisions/11/restore']);
});

test('restoring a draft just says restored', async () => {
  fakeApi({ ...ITEM, 'POST /items/7/revisions/12/restore': { body: { status: 'draft' } } });
  const view = page('/edit/7/revisions');
  await t.user.click((await view.findAllByRole('button', { name: /^Restore revision from / }))[0]);
  expect((await view.findByText(/^landed/)).textContent).toBe('landed: Restored.');
});

test('a declined confirmation restores nothing', async () => {
  confirmed = false;
  const calls = fakeApi(ITEM);
  const view = page('/edit/7/revisions');
  await t.user.click((await view.findAllByRole('button', { name: /^Restore revision from / }))[0]);
  expect(calls.some((c) => c.method === 'POST')).toBe(false);
  expect(view.queryByText(/^landed/)).toBeNull();
});

test('a refused restore says why and can be retried', async () => {
  fakeApi({ ...ITEM, 'POST /items/7/revisions/12/restore': { status: 409 } });
  const view = page('/edit/7/revisions');
  const button = (await view.findAllByRole('button', { name: /^Restore revision from / }))[0];
  await t.user.click(button);
  expect((await view.findByRole('alert')).textContent).toStartWith('Someone else changed this post.');
  expect((button as HTMLButtonElement).disabled).toBe(false);
});

const BLOCK = {
  ...SHELL,
  'GET /blocks/3/revisions': { body: LIST.slice(0, 2) },
  'GET /blocks/3': { body: { canEdit: true } },
  'GET /blocks/3/revisions/12': { body: { ...rev(12, 'Block', ''), body: undefined, content: '<p>new</p>\n' } },
  'GET /blocks/3/revisions/11': { body: { ...rev(11, 'Block', ''), body: undefined, content: '<p>old</p>\n' } },
};

test('a block revision compares its content', async () => {
  fakeApi(BLOCK);
  const view = page('/blocks/3/revisions');
  await view.findByRole('region', { name: 'From' });
  expect(marked(side(view, 'From'), 'diff-removed')).toEqual(['−Removed: <p>old</p>']);
});

test('restoring a block says how many items republish', async () => {
  fakeApi({ ...BLOCK, 'POST /blocks/3/revisions/11/restore': { body: { republished: 2 } } });
  const view = page('/blocks/3/revisions');
  await t.user.click((await view.findAllByRole('button', { name: /^Restore revision from / }))[1]);
  expect((await view.findByText(/^landed/)).textContent).toBe('landed: Restored. 2 items will be republished.');
});

test('restoring an unused block says nothing republishes', async () => {
  fakeApi({ ...BLOCK, 'POST /blocks/3/revisions/11/restore': { body: { republished: 0 } } });
  const view = page('/blocks/3/revisions');
  await t.user.click((await view.findAllByRole('button', { name: /^Restore revision from / }))[1]);
  expect((await view.findByText(/^landed/)).textContent).toBe('landed: Restored. No published items use this block.');
});
