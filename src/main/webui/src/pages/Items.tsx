import { type FormEvent, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { __, _n, _x, sprintf } from '@wordpress/i18n';
import { formatInstant, formatLocal } from '../i18n/index.ts';
import { type ApiError, type Item, type ItemStatus, type ItemSummary, type Site, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';

const PER_PAGE = 20;
const tabs = (): { status: ItemStatus | ''; label: string }[] => [
  { status: '', label: __('All', 'stillpress') },
  { status: 'draft', label: __('Drafts', 'stillpress') },
  { status: 'pending', label: _x('Pending', 'status tab', 'stillpress') },
  { status: 'future', label: _x('Scheduled', 'status tab', 'stillpress') },
  { status: 'publish', label: _x('Published', 'status tab', 'stillpress') },
  { status: 'private', label: _x('Private', 'status tab', 'stillpress') },
  { status: 'trash', label: _x('Trash', 'status tab', 'stillpress') },
];
/** An item's status, as one word. */
export const statusLabel = (s: string): string => ({
  draft: __('Draft', 'stillpress'), pending: __('Pending', 'stillpress'), future: __('Scheduled', 'stillpress'),
  publish: __('Published', 'stillpress'), private: __('Private', 'stillpress'), trash: __('Trash', 'stillpress'),
} as Record<string, string>)[s] ?? s;

interface List { items: ItemSummary[]; total: number; counts: Record<string, number> }

/** Posts or pages: status tabs with counts, search, pages of 20, add, trash and restore. */
export default function Items({ type }: { type: 'post' | 'page' }) {
  const navigate = useNavigate();
  const [status, setStatus] = useState<ItemStatus | ''>('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [list, setList] = useState<List | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [timeZone, setTimeZone] = useState('UTC');
  useEffect(() => { api<Site>('/site').then((s) => setTimeZone(s.timezone), () => {}); }, []);

  const load = (s = status, q = search, p = page) =>
    api<List>(`/items?type=${type}&status=${s}&search=${encodeURIComponent(q)}&page=${p}`)
      .then(setList, () => setMessage(__('Could not load the list.', 'stillpress')));
  useEffect(() => { setStatus(''); setSearch(''); setPage(1); load('', '', 1); }, [type]);

  function show(s: ItemStatus | '', p = 1) {
    setStatus(s);
    setPage(p);
    load(s, search, p);
  }

  async function add() {
    setMessage(null);
    try {
      const it = await api<Item>('/items', { method: 'POST', body: { type } });
      navigate(`/edit/${it.id}`);
    } catch (e) {
      setMessage((e as ApiError).status === 403 ? __('You cannot add one.', 'stillpress') : __('Could not add one.', 'stillpress'));
    }
  }

  async function act(it: ItemSummary, restore: boolean) {
    setMessage(null);
    try {
      await api(restore ? `/items/${it.id}/restore` : `/items/${it.id}`, { method: restore ? 'POST' : 'DELETE' });
      load();
    } catch (e) {
      const err = e as ApiError;
      setMessage(err.error === 'csrf' ? err.message! : err.status === 403 ? __('You cannot change this one.', 'stillpress') : __('Could not save the change.', 'stillpress'));
    }
  }

  const counts = list?.counts ?? {};
  const all = Object.entries(counts).filter(([s]) => s !== 'trash').reduce((n, [, c]) => n + c, 0);
  const pages = list ? Math.max(1, Math.ceil(list.total / PER_PAGE)) : 1;

  return (
    <Shell>
      <div className="heading-row">
        <h1>{type === 'page' ? __('Pages', 'stillpress') : __('Posts', 'stillpress')}</h1>
        <button type="button" className="primary" onClick={add}>{type === 'page' ? __('Add new page', 'stillpress') : __('Add new post', 'stillpress')}</button>
      </div>
      {message && <p role="alert" className="error">{message}</p>}
      <nav aria-label={__('Filter by status', 'stillpress')} className="tabs">
        {tabs().filter((t) => t.status === '' || t.status === status || counts[t.status]).map((t) => (
          <button key={t.status} type="button" aria-pressed={status === t.status} onClick={() => show(t.status)}>
            {sprintf(__('%1$s (%2$d)', 'stillpress'), t.label, t.status === '' ? all : counts[t.status] ?? 0)}
          </button>
        ))}
      </nav>
      <form role="search" onSubmit={(e: FormEvent) => { e.preventDefault(); setPage(1); load(status, search, 1); }}>
        <Field label={type === 'page' ? __('Search pages', 'stillpress') : __('Search posts', 'stillpress')} name="search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
      </form>
      {list && (
        <>
          <p className="muted" role="status">{sprintf(type === 'page' ? _n('%d page', '%d pages', list.total, 'stillpress') : _n('%d post', '%d posts', list.total, 'stillpress'), list.total)}</p>
          <table className="table">
            <thead><tr><th>{__('Title', 'stillpress')}</th><th>{__('Author', 'stillpress')}</th><th>{__('Status', 'stillpress')}</th><th>{__('Date', 'stillpress')}</th><th /></tr></thead>
            <tbody>
              {list.items.map((it) => (
                <tr key={it.id}>
                  <td>{it.status === 'trash' ? (it.title || __('(no title)', 'stillpress')) : <Link to={`/edit/${it.id}`}>{it.title || __('(no title)', 'stillpress')}</Link>}</td>
                  <td>{it.author ?? '—'}</td>
                  <td>{statusLabel(it.status)}</td>
                  <td>{it.date ? formatLocal(it.date) : it.modified && formatInstant(it.modified, timeZone)}</td>
                  <td>
                    {it.status === 'trash'
                      ? <button type="button" onClick={() => act(it, true)} aria-label={sprintf(__('Restore “%s”', 'stillpress'), it.title || __('untitled', 'stillpress'))}>{__('Restore', 'stillpress')}</button>
                      : <button type="button" onClick={() => act(it, false)} aria-label={sprintf(__('Move “%s” to the trash', 'stillpress'), it.title || __('untitled', 'stillpress'))}>{_x('Trash', 'verb', 'stillpress')}</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {pages > 1 && (
            <nav aria-label={__('Pagination', 'stillpress')}>
              <button type="button" disabled={page <= 1} onClick={() => show(status, page - 1)}>{__('Previous', 'stillpress')}</button>{' '}
              <span>{sprintf(__('Page %1$d of %2$d', 'stillpress'), page, pages)}</span>{' '}
              <button type="button" disabled={page >= pages} onClick={() => show(status, page + 1)}>{__('Next', 'stillpress')}</button>
            </nav>
          )}
        </>
      )}
    </Shell>
  );
}
