import { beforeAll, expect, test, vi } from 'vitest';
import type { Media } from '../api.ts';
import { fakeApi, usePages } from '../testapi.ts';

const t = usePages();
let ml: typeof import('./MediaLibrary.tsx');
beforeAll(async () => {
  window.matchMedia ??= () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} }) as never;
  ml = await import('./MediaLibrary.tsx');
});

const CAT: Media = { id: 1, url: '/wp-content/uploads/cat.jpg', mimeType: 'image/jpeg', width: 640, height: 480, title: 'Cat', alt: 'A cat' };
const DOG: Media = { id: 2, url: '/wp-content/uploads/dog.png', mimeType: 'image/png', width: null, height: null, title: 'Dog', alt: null };
const SONG: Media = { id: 3, url: '/wp-content/uploads/song.mp3', mimeType: 'audio/mpeg', width: null, height: null, title: 'Song', alt: null };
const GONE: Media = { id: 4, url: null, mimeType: 'image/jpeg', width: null, height: null, title: 'Gone', alt: null };
const list = (items: Media[], total = items.length) => ({ body: { items, total } });
const IMAGES = '/media?type=image&search=&page=1&perPage=40';

function open(props: Partial<Parameters<typeof ml.default>[0]> = {}) {
  const onSelect = vi.fn();
  const view = t.rtl.render(<ml.default allowedTypes={['image']} onSelect={onSelect}
    render={({ open: o }) => <button type="button" onClick={o}>Open library</button>} {...props} />);
  return { view, onSelect, opened: t.user.click(view.getByRole('button', { name: 'Open library' })) };
}
const tile = (name: string) => t.rtl.screen.getByRole('button', { name });

test('lists the media of the allowed type, skipping files that are missing', async () => {
  const calls = fakeApi({ [`GET ${IMAGES}`]: list([CAT, DOG, GONE]) });
  const { opened } = open();
  await opened;
  expect(await t.rtl.screen.findByRole('button', { name: 'A cat' })).toBeTruthy();
  expect(tile('Dog')).toBeTruthy();
  expect(t.rtl.screen.queryByRole('button', { name: 'Gone' })).toBeNull();
  expect(calls.map((c) => c.path)).toEqual([IMAGES]);
});

test('picking an image hands it over in the block library shape and closes', async () => {
  fakeApi({ [`GET ${IMAGES}`]: list([CAT, DOG]) });
  const { onSelect, opened } = open({ title: 'Choose an image' });
  await opened;
  expect(t.rtl.screen.getByRole('dialog', { name: 'Choose an image' })).toBeTruthy();
  await t.user.click(await t.rtl.screen.findByRole('button', { name: 'A cat' }));
  expect(t.rtl.screen.getByText('cat.jpg · 640×480')).toBeTruthy();
  await t.user.click(t.rtl.screen.getByRole('button', { name: 'Select' }));
  expect(onSelect).toHaveBeenCalledWith({ id: 1, url: CAT.url, alt: 'A cat', mime: 'image/jpeg', type: 'image', width: 640, height: 480 });
  expect(t.rtl.screen.queryByRole('dialog')).toBeNull();
});

test('a mix of types filters in the browser; several can be picked and unpicked', async () => {
  const calls = fakeApi({ 'GET /media?type=&search=&page=1&perPage=40': list([CAT, SONG, { ...DOG, mimeType: 'text/plain', title: 'Notes' }]) });
  const { onSelect, opened } = open({ allowedTypes: ['image', 'audio/mpeg'], multiple: true, value: 1 });
  await opened;
  await t.user.click(await t.rtl.screen.findByRole('button', { name: 'Song' }));
  expect(t.rtl.screen.queryByRole('button', { name: 'Notes' })).toBeNull();
  expect(tile('A cat').getAttribute('aria-pressed')).toBe('true');
  await t.user.click(tile('A cat'));
  expect(tile('A cat').getAttribute('aria-pressed')).toBe('false');
  await t.user.click(tile('A cat'));
  await t.user.click(t.rtl.screen.getByRole('button', { name: 'Select' }));
  expect(onSelect.mock.calls[0][0].map((m: { id: number }) => m.id)).toEqual([3, 1]);
  expect(calls[0].path).toBe('/media?type=&search=&page=1&perPage=40');
});

test('searching asks the server for that text from page one', async () => {
  const calls = fakeApi({ [`GET ${IMAGES}`]: list([CAT]), 'GET /media?type=image&search=d%26g&page=1&perPage=40': list([DOG]) });
  const { opened } = open();
  await opened;
  await t.user.type(t.rtl.screen.getByLabelText('Search media'), 'd&g{Enter}');
  expect(await t.rtl.screen.findByRole('button', { name: 'Dog' })).toBeTruthy();
  expect(calls.map((c) => c.path)).toContain('/media?type=image&search=d%26g&page=1&perPage=40');
});

test('pages through a long library', async () => {
  fakeApi({ [`GET ${IMAGES}`]: list([CAT], 41), 'GET /media?type=image&search=&page=2&perPage=40': list([DOG], 41) });
  const { opened } = open();
  await opened;
  expect(await t.rtl.screen.findByText('Page 1 of 2')).toBeTruthy();
  expect((t.rtl.screen.getByRole('button', { name: 'Previous' }) as HTMLButtonElement).disabled).toBe(true);
  await t.user.click(t.rtl.screen.getByRole('button', { name: 'Next' }));
  expect(await t.rtl.screen.findByRole('button', { name: 'Dog' })).toBeTruthy();
  expect(t.rtl.screen.getByText('Page 2 of 2')).toBeTruthy();
  await t.user.click(t.rtl.screen.getByRole('button', { name: 'Previous' }));
  expect(await t.rtl.screen.findByRole('button', { name: 'A cat' })).toBeTruthy();
});

test('an empty library says so', async () => {
  fakeApi({ [`GET ${IMAGES}`]: list([]) });
  await open().opened;
  expect((await t.rtl.screen.findByRole('status')).textContent).toBe('Nothing here yet.');
});

test('a library that cannot be loaded says so', async () => {
  fakeApi({ [`GET ${IMAGES}`]: { status: 500 } });
  await open().opened;
  expect((await t.rtl.screen.findByRole('alert')).textContent).toBe('Could not load the media library.');
});

test('editing alt text and title saves them and the pick carries the edits', async () => {
  const calls = fakeApi({
    [`GET ${IMAGES}`]: list([CAT]),
    'PUT /media/1': (body) => ({ body: { ...CAT, ...(body as object) } }),
  });
  const { onSelect, opened } = open();
  await opened;
  await t.user.click(await t.rtl.screen.findByRole('button', { name: 'A cat' }));
  const alt = t.rtl.screen.getByLabelText('Alternative text');
  await t.user.clear(alt);
  await t.user.type(alt, 'A sleepy cat');
  await t.user.clear(t.rtl.screen.getByLabelText('Title'));
  await t.user.type(t.rtl.screen.getByLabelText('Title'), 'Kitty');
  await t.user.click(t.rtl.screen.getByRole('button', { name: 'Save details' }));
  expect(await t.rtl.screen.findByRole('button', { name: 'A sleepy cat' })).toBeTruthy();
  expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ title: 'Kitty', alt: 'A sleepy cat' });
  await t.user.click(t.rtl.screen.getByRole('button', { name: 'Select' }));
  expect(onSelect.mock.calls[0][0].alt).toBe('A sleepy cat');
});

test('details that cannot be saved say so', async () => {
  fakeApi({ [`GET ${IMAGES}`]: list([CAT]), 'PUT /media/1': { status: 500 } });
  await open().opened;
  await t.user.click(await t.rtl.screen.findByRole('button', { name: 'A cat' }));
  await t.user.click(t.rtl.screen.getByRole('button', { name: 'Save details' }));
  expect((await t.rtl.screen.findByRole('alert')).textContent).toBe('Could not save the details.');
});

const fileInput = () => document.querySelector('input[type="file"]') as HTMLInputElement;

test('an upload posts each file, selects the last and reloads the list', async () => {
  const added = { ...DOG, id: 9 };
  const calls = fakeApi({
    [`GET ${IMAGES}`]: () => list(calls.some((c) => c.method === 'POST') ? [CAT, added] : [CAT]),
    'POST /media': { status: 201, body: added },
  });
  await open().opened;
  await t.rtl.screen.findByRole('button', { name: 'A cat' });
  expect(fileInput().accept).toBe('image/*');
  await t.user.upload(fileInput(), new File(['x'], 'dog.png', { type: 'image/png' }));
  await t.rtl.waitFor(() => expect(tile('Dog').getAttribute('aria-pressed')).toBe('true'));
  expect(tile('A cat').getAttribute('aria-pressed')).toBe('false');
  const post = calls.find((c) => c.method === 'POST');
  expect(((post?.body as FormData).get('file') as File).name).toBe('dog.png');
  expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([`GET ${IMAGES}`, 'POST /media', `GET ${IMAGES}`]);
});

test.each([
  [{ status: 400, body: { error: 'type_not_allowed' } }, 'This file type cannot be uploaded.'],
  [{ status: 403, body: { error: 'forbidden' } }, 'You cannot upload files.'],
  [{ status: 413, body: { error: 'too_large' } }, 'The file is too large.'],
])('a refused upload shows why (%o)', async (reply, text) => {
  fakeApi({ [`GET ${IMAGES}`]: list([CAT]), 'POST /media': reply });
  await open().opened;
  await t.rtl.screen.findByRole('button', { name: 'A cat' });
  await t.user.upload(fileInput(), new File(['x'], 'a.png', { type: 'image/png' }));
  expect((await t.rtl.screen.findByRole('alert')).textContent).toBe(text);
});

test('uploadError falls back to the server message, then to a generic one', () => {
  expect(ml.uploadError({ status: 500, message: 'Disk full' })).toBe('Disk full');
  expect(ml.uploadError(new TypeError('offline'))).toBe('The upload failed.');
  expect(ml.uploadError({ status: 500 })).toBe('The upload failed.');
});

test('closing the modal picks nothing', async () => {
  fakeApi({ [`GET ${IMAGES}`]: list([CAT]) });
  const { onSelect, opened } = open();
  await opened;
  await t.user.click(t.rtl.screen.getByRole('button', { name: 'Close' }));
  expect(t.rtl.screen.queryByRole('dialog')).toBeNull();
  expect(onSelect).not.toHaveBeenCalled();
});
