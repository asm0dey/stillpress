import { beforeAll, expect, test, vi } from 'vitest';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let w: typeof import('./workspace.tsx');
beforeAll(async () => {
  window.matchMedia ??= () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} }) as never;
  w = await import('./workspace.tsx');
});

type Settings = ReturnType<typeof import('./workspace.tsx').useEditorSettings>;

/** Renders useEditorSettings under a router, handing back what it returns. */
function settingsOf(canPublish: boolean, unsaved = false) {
  const got: { current?: Settings } = {};
  function Probe() {
    got.current = w.useEditorSettings(canPublish, unsaved);
    return null;
  }
  const view = t.rtl.render(
    <t.router.MemoryRouter initialEntries={['/posts/1']}>
      <t.router.Routes>
        <t.router.Route path="/posts/1" element={<Probe />} />
        <t.router.Route path="/blocks/:id" element={<p>block editor</p>} />
      </t.router.Routes>
    </t.router.MemoryRouter>,
  );
  return { view, settings: () => got.current! };
}

test('media upload posts the files and hands the block library their picks', async () => {
  const calls = fakeApi({
    'GET /blocks?content=true': { body: [] },
    'POST /media': { status: 201, body: { id: 5, url: '/u/a.png', mimeType: 'image/png', width: 2, height: 1, title: 'a', alt: '' } },
  });
  const upload = settingsOf(true).settings().mediaUpload!;
  const onFileChange = vi.fn();
  upload({ filesList: [new File(['x'], 'a.png', { type: 'image/png' })], onFileChange });
  await t.rtl.waitFor(() => expect(onFileChange).toHaveBeenCalledWith([{ id: 5, url: '/u/a.png', alt: '', mime: 'image/png', type: 'image', width: 2, height: 1 }]));
  expect(((calls.find((c) => c.method === 'POST')?.body as FormData).get('file') as File).name).toBe('a.png');
});

test('a refused media upload reports why', async () => {
  fakeApi({ 'GET /blocks?content=true': { body: [] }, 'POST /media': { status: 413, body: { error: 'too_large' } } });
  const onError = vi.fn();
  settingsOf(true).settings().mediaUpload!({ filesList: [new File(['x'], 'a.png')], onFileChange: vi.fn(), onError });
  await t.rtl.waitFor(() => expect(onError).toHaveBeenCalledWith('The file is too large.'));
});

test('without the right to publish there is no media upload', () => {
  fakeApi({ 'GET /blocks?content=true': { body: [] } });
  expect(settingsOf(false).settings().mediaUpload).toBeUndefined();
});

test('link suggestions are the published posts and pages with a permalink', async () => {
  const calls = fakeApi({
    'GET /blocks?content=true': { body: [] },
    'GET /items?type=post&status=publish&search=a%20b': { body: { items: [{ id: 1, title: 'Post', type: 'post', permalink: '/p' }, { id: 2, title: 'X', type: 'post', permalink: null }] } },
    'GET /items?type=page&status=publish&search=a%20b': { body: { items: [{ id: 3, title: '', type: 'page', permalink: '/about' }] } },
  });
  expect(await settingsOf(true).settings().__experimentalFetchLinkSuggestions('a b')).toEqual([
    { id: 1, url: '/p', title: 'Post', type: 'post', kind: 'post-type' },
    { id: 3, url: '/about', title: '(no title)', type: 'page', kind: 'post-type' },
  ]);
  expect(calls.filter((c) => c.path.startsWith('/items'))).toHaveLength(2);
});

test('"Edit original" of a reusable block opens its screen', async () => {
  fakeApi({ 'GET /blocks?content=true': { body: [] } });
  const { view, settings } = settingsOf(true);
  t.rtl.act(() => settings().onNavigateToEntityRecord({ postId: 'x', postType: 'page' }));
  expect(view.queryByText('block editor')).toBeNull();
  t.rtl.act(() => settings().onNavigateToEntityRecord({ postId: '7', postType: 'wp_block' }));
  expect(await view.findByText('block editor')).toBeTruthy();
});

test('with unsaved edits, "Edit original" asks first and stays when refused', () => {
  fakeApi({ 'GET /blocks?content=true': { body: [] } });
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  const { view, settings } = settingsOf(true, true);
  t.rtl.act(() => settings().onNavigateToEntityRecord({ postId: 7, postType: 'wp_block' }));
  expect(confirm).toHaveBeenCalledWith('Leave without saving your changes?');
  expect(view.queryByText('block editor')).toBeNull();
  confirm.mockRestore();
});

/** Renders useEditing, handing back what it returns. */
function editing(put: () => Promise<unknown> | null) {
  const got: { current?: ReturnType<typeof w.useEditing> } = {};
  function Probe() {
    got.current = w.useEditing(put);
    return null;
  }
  t.rtl.render(<Probe />);
  return () => got.current!;
}

test('every minute an edit is autosaved once; a failed one is retried at the next tick', async () => {
  vi.useFakeTimers();
  try {
    let fail = true;
    const put = vi.fn(() => (fail ? Promise.reject(new Error('offline')) : Promise.resolve()));
    const e = editing(put);
    await t.rtl.act(() => vi.advanceTimersByTimeAsync(60_000));
    expect(put).not.toHaveBeenCalled();
    t.rtl.act(() => e().markDirty());
    await t.rtl.act(() => vi.advanceTimersByTimeAsync(60_000));
    expect(put).toHaveBeenCalledTimes(1);
    fail = false;
    await t.rtl.act(() => vi.advanceTimersByTimeAsync(60_000));
    await t.rtl.act(() => vi.advanceTimersByTimeAsync(60_000));
    expect(put).toHaveBeenCalledTimes(2);
  } finally { vi.useRealTimers(); }
});

test('an explicit autosave hears the refusal; nothing to send sends nothing', async () => {
  const e = editing(() => Promise.reject(new Error('refused')));
  t.rtl.act(() => e().markDirty());
  await expect(e().autosave(true)).rejects.toThrow('refused');
  const none = editing(() => null);
  t.rtl.act(() => none().markDirty());
  await expect(none().autosave(true)).resolves.toBeUndefined();
});

test('unsaved edits make leaving the page ask; a save clears that', () => {
  const e = editing(() => null);
  const leave = () => {
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    return event.defaultPrevented;
  };
  expect(leave()).toBe(false);
  t.rtl.act(() => e().markDirty());
  expect(e().unsaved).toBe(true);
  expect(leave()).toBe(true);
  t.rtl.act(() => e().saved(e().edits.current.mark(), ''));
  expect(e().unsaved).toBe(false);
  expect(leave()).toBe(false);
});

test('a save message lists the refused HTML', () => {
  const view = t.rtl.render(<w.EditorNotices message={{ kind: 'alert', text: 'Refused:', list: ['<script>'] }} onDismiss={() => {}}
    autosaveAt="2026-01-01" noun="post" onViewAutosave={() => {}} onDismissAutosave={() => {}} />);
  expect(view.getByRole('alert').textContent).toContain('<script>');
  expect(view.getByText(/There is an autosave of this post/)).toBeTruthy();
});

test('the block library\'s notices show as snackbars', async () => {
  const { dispatch } = await import('@wordpress/data');
  const { store } = await import('@wordpress/notices');
  const view = t.rtl.render(<w.EditorFrame><p>canvas</p></w.EditorFrame>);
  await t.rtl.act(async () => { await dispatch(store).createNotice('info', 'Copied!', { type: 'snackbar', id: 'copied' }); });
  await t.rtl.waitFor(() => expect(view.getByTestId('snackbar-list').textContent).toContain('Copied!'));
  await t.rtl.act(async () => { await dispatch(store).removeNotice('copied'); });
});
