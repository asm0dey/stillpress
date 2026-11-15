import { beforeAll, expect, test, vi } from 'vitest';
import type { Item, SessionUser, TermView } from '../api.ts';
import { usePages } from '../testapi.ts';
import type { Draft } from './Sidebar.tsx';

const t = usePages();
let Sidebar: typeof import('./Sidebar.tsx').default;
beforeAll(async () => {
  // jsdom lacks it; Gutenberg's popovers and tab panels observe their size
  globalThis.ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} } as never;
  Sidebar = (await import('./Sidebar.tsx')).default;
});

const POST: Item = {
  id: 5, type: 'post', title: 'Hello', slug: 'hello', status: 'pending', body: '', bodyFormat: 'blocks', excerpt: '', date: null,
  permalink: null, categories: [11], tags: [], authorId: 1, author: 'ada', parentId: null, commentStatus: 'open', menuOrder: 0,
  modifiedAt: '2026-11-01T10:00:00Z', autosave: null, canEdit: true,
};
const DRAFT: Draft = {
  slug: 'hello', excerpt: '', visibility: 'public', date: null, dateChanged: false, categories: [11], tags: [], authorId: 1,
  commentStatus: 'open', parentId: null, menuOrder: 0,
};
const CATS: TermView[] = [
  { id: 10, name: 'News', slug: 'news', parentId: null }, { id: 11, name: 'Tech', slug: 'tech', parentId: 10 },
  { id: 12, name: 'Life', slug: 'life', parentId: null },
];
const TAGS: TermView[] = [{ id: 2, name: 'Java', slug: 'java', parentId: null }];
const as = (role: SessionUser['role']) => ({ id: 1, login: 'ada', displayName: 'Ada', email: 'a@example.org', role, locale: 'en', enrollRequired: false }) as SessionUser;

function render(role: SessionUser['role'], over: Partial<Parameters<typeof Sidebar>[0]> = {}) {
  const onChange = vi.fn();
  const view = t.rtl.render(<Sidebar item={POST} session={as(role)} draft={DRAFT} onChange={onChange} categories={CATS}
    onAddCategory={async () => {}} tags={TAGS} users={null} pages={[]} {...over} />);
  return { view, onChange };
}

test('shows the status and nests categories under their parent, checked as in the draft', () => {
  const { view } = render('author');
  expect(view.getByText('Pending review')).toBeTruthy();
  const tree = view.getByRole('group', { name: 'Categories' });
  expect([...tree.querySelectorAll('label')].map((l) => l.textContent)).toEqual(['News', 'Tech', 'Life']);
  expect((view.getByRole('checkbox', { name: 'Tech' }) as HTMLInputElement).checked).toBe(true);
});

test('unticking a category drops it from the draft', async () => {
  const { view, onChange } = render('author');
  await t.user.click(view.getByRole('checkbox', { name: 'Tech' }));
  expect(onChange).toHaveBeenLastCalledWith({ categories: [] });
});

test('without the user list the author is shown, not chosen', () => {
  const { view } = render('author');
  expect(view.queryByLabelText('Author')).toBeNull();
  expect(view.getByText('ada')).toBeTruthy();
});

test('a contributor may only pick existing tags and has no visibility or new categories', async () => {
  const { view, onChange } = render('contributor');
  expect(view.getByText('Only existing tags can be added.')).toBeTruthy();
  expect(view.queryByLabelText('Visibility')).toBeNull();
  expect(view.queryByRole('button', { name: 'Add new category' })).toBeNull();
  const tag = view.getByLabelText('Add tag');
  await t.user.type(tag, 'rust{Enter}');
  expect(onChange).not.toHaveBeenCalled();
  await t.user.type(tag, '{Backspace>4}java{Enter}');
  expect(onChange).toHaveBeenLastCalledWith({ tags: ['java'] });
});

test('picking a date marks it changed', async () => {
  const { view, onChange } = render('author', { draft: { ...DRAFT, date: '2026-05-02T11:00:00' } });
  await t.user.click(view.getByRole('button', { expanded: false, description: 'Publish' }));
  expect(await view.findByText('Time zone: UTC')).toBeTruthy();
  await t.user.click(view.getByRole('button', { name: 'May 20, 2026' }));
  expect(onChange).toHaveBeenLastCalledWith({ date: '2026-05-20T11:00:00', dateChanged: true });
});

test('a page has attributes instead of terms; an empty order is 0', async () => {
  const { view, onChange } = render('editor', { item: { ...POST, type: 'page', status: 'draft' }, draft: { ...DRAFT, menuOrder: 3 } });
  expect(view.getByText('Draft')).toBeTruthy();
  expect(view.queryByLabelText('Add tag')).toBeNull();
  expect(view.getByRole('tab', { name: 'Page' })).toBeTruthy();
  await t.user.clear(view.getByLabelText('Order'));
  expect(onChange).toHaveBeenLastCalledWith({ menuOrder: 0 });
});

test('the Block tab shows the block inspector', async () => {
  const { view } = render('author');
  await t.user.click(view.getByRole('tab', { name: 'Block' }));
  expect(view.queryByLabelText('Slug')).toBeNull();
});
