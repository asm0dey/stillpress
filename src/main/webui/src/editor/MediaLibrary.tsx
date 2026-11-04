import { type ReactNode, useEffect, useState } from 'react';
import { Button, FormFileUpload, Modal, Notice, Spinner, TextControl } from '@wordpress/components';
import { addFilter } from '@wordpress/hooks';
import { __, sprintf } from '@wordpress/i18n';
import { asApiError, type ApiError, type Media, api } from '../api.ts';
import { useLatest } from '../useLatest.ts';

/** What the block library's media blocks expect from a pick. */
export interface Picked { id: number; url: string; alt: string; mime: string; type: string; width?: number; height?: number }
export const picked = (m: Media): Picked => ({
  id: m.id, url: m.url ?? '', alt: m.alt ?? '', mime: m.mimeType ?? '', type: (m.mimeType ?? '').split('/')[0],
  width: m.width ?? undefined, height: m.height ?? undefined,
});

/** Uploads files one by one; a refused one stops with the server's reason. */
export async function upload(files: File[]): Promise<Media[]> {
  const out: Media[] = [];
  for (const file of files) {
    const body = new FormData();
    body.append('file', file);
    out.push(await api<Media>('/media', { method: 'POST', body }));
  }
  return out;
}

export function uploadError(e: unknown): string {
  const err = asApiError(e);
  if (err.error === 'type_not_allowed') return __('This file type cannot be uploaded.', 'stillpress');
  if (err.status === 403) return __('You cannot upload files.', 'stillpress');
  if (err.status === 413) return __('The file is too large.', 'stillpress');
  return err.message ?? __('The upload failed.', 'stillpress');
}

interface Props {
  allowedTypes?: string[];
  multiple?: boolean | string;
  value?: number | number[];
  title?: string;
  onSelect: (media: Picked | Picked[]) => void;
  render: (o: { open: () => void }) => ReactNode;
}

const PER_PAGE = 40;

/** The block library's MediaUpload: a modal over GET /admin/api/media with upload and alt/title editing. */
export default function MediaLibrary({ allowedTypes, multiple, value, title, onSelect, render }: Props) {
  const [open, setOpen] = useState(false);
  return (
    <>
      {render({ open: () => setOpen(true) })}
      {open && (
        <Library allowedTypes={allowedTypes} multiple={!!multiple} value={value} title={title ?? __('Media library', 'stillpress')}
          onClose={() => setOpen(false)} onSelect={(m) => { setOpen(false); onSelect(m); }} />
      )}
    </>
  );
}

function Library({ allowedTypes, multiple, value, title, onClose, onSelect }: {
  allowedTypes?: string[]; multiple: boolean; value?: number | number[]; title: string;
  onClose: () => void; onSelect: (m: Picked | Picked[]) => void;
}) {
  // one kind of media filters on the server; a mix filters here
  const type = allowedTypes?.length === 1 && ['image', 'audio', 'video'].includes(allowedTypes[0]) ? allowedTypes[0] : '';
  const allowed = (m: Media) => !allowedTypes?.length
    || allowedTypes.some((t) => m.mimeType === t || (m.mimeType ?? '').startsWith(t + '/'));
  const [list, setList] = useState<{ items: Media[]; total: number } | null>(null);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<number[]>(value === undefined ? [] : ([] as number[]).concat(value));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [meta, setMeta] = useState<{ title: string; alt: string }>({ title: '', alt: '' });

  const latest = useLatest();
  const load = (p = page, q = search) =>
    latest(api<{ items: Media[]; total: number }>(`/media?type=${type}&search=${encodeURIComponent(q)}&page=${p}&perPage=${PER_PAGE}`))
      .then(setList, () => setError(__('Could not load the media library.', 'stillpress')));
  useEffect(() => { load(); }, []);

  // imported media whose file was missing has no url
  const items = (list?.items ?? []).filter((m): m is Media & { url: string } => !!m.url && allowed(m));
  const current = items.find((m) => m.id === selected[selected.length - 1]);
  useEffect(() => { setMeta({ title: current?.title ?? '', alt: current?.alt ?? '' }); }, [current?.id]);

  function toggle(id: number) {
    setSelected(multiple ? (selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id]) : [id]);
  }

  async function add(files: FileList | null) {
    if (!files?.length) return;
    setBusy(true);
    setError(null);
    try {
      const added = await upload(Array.from(files));
      setSelected(multiple ? [...selected, ...added.map((m) => m.id)] : [added[added.length - 1].id]);
      setPage(1);
      load(1, search);
    } catch (e) { setError(uploadError(e)); }
    setBusy(false);
  }

  async function saveMeta() {
    if (!current) return;
    setError(null);
    try {
      const m = await api<Media>(`/media/${current.id}`, { method: 'PUT', body: meta });
      setList(list && { ...list, items: list.items.map((x) => (x.id === m.id ? m : x)) });
    } catch { setError(__('Could not save the details.', 'stillpress')); }
  }

  function choose() {
    const chosen = selected.map((id) => items.find((m) => m.id === id)).filter((m) => !!m)
      .map((m) => picked(m.id === current?.id ? { ...m, ...meta } : m));
    if (chosen.length) onSelect(multiple ? chosen : chosen[0]);
  }

  const pages = list ? Math.max(1, Math.ceil(list.total / PER_PAGE)) : 1;
  return (
    <Modal title={title} onRequestClose={onClose} size="fill" className="sp-media">
      <div className="sp-media__bar">
        <form role="search" onSubmit={(e) => { e.preventDefault(); setPage(1); load(1, search); }}>
          <TextControl __next40pxDefaultSize label={__('Search media', 'stillpress')} value={search} onChange={setSearch} />
        </form>
        <FormFileUpload __next40pxDefaultSize multiple={multiple} accept={allowedTypes?.map((t) => (t.includes('/') ? t : t + '/*')).join(',')}
          onChange={(e) => add((e.target as HTMLInputElement).files)} render={({ openFileDialog }) => (
            <Button variant="secondary" onClick={openFileDialog} isBusy={busy} disabled={busy}>{__('Upload', 'stillpress')}</Button>
          )} />
      </div>
      {error && <Notice status="error" isDismissible={false}><span role="alert">{error}</span></Notice>}
      {!list ? <Spinner /> : (
        <div className="sp-media__body">
          <ul className="sp-media__grid" aria-label={__('Media', 'stillpress')}>
            {items.map((m) => (
              <li key={m.id}>
                <button type="button" aria-pressed={selected.includes(m.id)} onClick={() => toggle(m.id)}
                  className={selected.includes(m.id) ? 'is-selected' : undefined}>
                  {m.mimeType?.startsWith('image/') ? <img src={m.url} alt={m.alt || m.title || ''} loading="lazy" />
                    : <span>{m.title || m.url.split('/').pop()}</span>}
                </button>
              </li>
            ))}
            {items.length === 0 && <li className="muted" role="status">{__('Nothing here yet.', 'stillpress')}</li>}
          </ul>
          {current && (
            <aside className="sp-media__details" aria-label={__('Details', 'stillpress')}>
              <p className="hint">{current.url.split('/').pop()}{current.width ? ` · ${current.width}×${current.height}` : ''}</p>
              <TextControl __next40pxDefaultSize label={__('Alternative text', 'stillpress')} value={meta.alt} onChange={(alt) => setMeta({ ...meta, alt })} />
              <TextControl __next40pxDefaultSize label={__('Title', 'stillpress')} value={meta.title} onChange={(t) => setMeta({ ...meta, title: t })} />
              <Button variant="secondary" onClick={saveMeta}>{__('Save details', 'stillpress')}</Button>
            </aside>
          )}
        </div>
      )}
      <div className="sp-media__footer">
        {pages > 1 && (
          <nav aria-label={__('Pagination', 'stillpress')}>
            <Button disabled={page <= 1} onClick={() => { setPage(page - 1); load(page - 1); }}>{__('Previous', 'stillpress')}</Button>
            <span> {sprintf(__('Page %1$d of %2$d', 'stillpress'), page, pages)} </span>
            <Button disabled={page >= pages} onClick={() => { setPage(page + 1); load(page + 1); }}>{__('Next', 'stillpress')}</Button>
          </nav>
        )}
        <Button variant="primary" disabled={!selected.length} onClick={choose}>{__('Select', 'stillpress')}</Button>
      </div>
    </Modal>
  );
}

addFilter('editor.MediaUpload', 'stillpress/media', () => MediaLibrary);
