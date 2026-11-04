/// <reference types="bun" />
import { beforeAll, expect, test } from 'bun:test';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let page: () => ReturnType<typeof t.rtl.render>;
beforeAll(async () => {
  const { default: Blocks } = await import('./Blocks.tsx');
  const { MemoryRouter, Route, Routes } = t.router;
  page = () => t.rtl.render(
    <MemoryRouter initialEntries={['/blocks']}>
      <Routes>
        <Route path="/blocks" element={<Blocks />} />
        <Route path="/blocks/:id" element={<p>editing block</p>} />
      </Routes>
    </MemoryRouter>,
  );
});

const as = (role: string, blocks: unknown[] = []) => ({
  'GET /session': { body: { role } },
  'GET /site': { body: { importAllowed: false, timezone: 'UTC' } },
  'GET /blocks': { body: blocks },
});
const BLOCKS = [
  { id: 1, title: 'Footer', author: 'ada', usedBy: 1, modifiedAt: '2026-11-03T10:00:00Z', canEdit: true },
  { id: 2, title: '', author: null, usedBy: 3, modifiedAt: '2026-11-02T10:00:00Z', canEdit: false },
];

test('lists the blocks with their use', async () => {
  fakeApi(as('author', BLOCKS));
  const view = page();
  const rows = (await view.findAllByRole('row')).slice(1).map((r) => [...r.querySelectorAll('td')].slice(0, 3).map((c) => c.textContent));
  expect(rows).toEqual([['Footer', 'ada', '1 item'], ['(no title) (view only)', '—', '3 items']]);
});

test('no blocks yet', async () => {
  fakeApi(as('author'));
  expect(await page().findByText('No reusable blocks yet.')).toBeTruthy();
});

test('a list that cannot be loaded says so', async () => {
  fakeApi({ ...as('author'), 'GET /blocks': { status: 500 } });
  expect((await page().findByRole('alert')).textContent).toBe('Could not load the list.');
});

test('adding a block opens it', async () => {
  const calls = fakeApi({ ...as('author'), 'POST /blocks': { status: 201, body: { id: 9 } } });
  const view = page();
  await t.user.click(await view.findByRole('button', { name: 'Add new block' }));
  expect(await view.findByText('editing block')).toBeTruthy();
  expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ title: '', content: '' });
});

test('a contributor cannot add blocks', async () => {
  fakeApi(as('contributor', BLOCKS));
  const view = page();
  await view.findByText('Footer');
  expect(view.queryByRole('button', { name: 'Add new block' })).toBeNull();
});

for (const [name, reply, text] of [
  ['a forbidden add', { status: 403 }, 'You cannot add one.'],
  ['a failed add', { status: 500 }, 'Could not add one.'],
  ['an expired session token', { status: 400 }, 'Your session token has expired. Reload the page and try again.'],
] as const) {
  test(`${name} says so`, async () => {
    fakeApi({ ...as('author'), 'POST /blocks': reply });
    const view = page();
    await t.user.click(await view.findByRole('button', { name: 'Add new block' }));
    expect((await view.findByRole('alert')).textContent).toBe(text);
  });
}
