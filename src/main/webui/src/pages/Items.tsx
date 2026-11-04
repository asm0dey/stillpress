import { type FormEvent, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { type ApiError, type Item, type ItemStatus, type ItemSummary, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';

const PER_PAGE = 20;
const TABS: { status: ItemStatus | ''; label: string }[] = [
  { status: '', label: 'All' },
  { status: 'draft', label: 'Drafts' },
  { status: 'pending', label: 'Pending' },
  { status: 'future', label: 'Scheduled' },
  { status: 'publish', label: 'Published' },
  { status: 'private', label: 'Private' },
  { status: 'trash', label: 'Trash' },
];
const LABEL: Record<string, string> = {
  draft: 'Draft', pending: 'Pending', future: 'Scheduled', publish: 'Published', private: 'Private', trash: 'Trash',
};

interface List { items: ItemSummary[]; total: number; counts: Record<string, number> }

/** Posts or pages: status tabs with counts, search, pages of 20, add, trash and restore. */
export default function Items({ type }: { type: 'post' | 'page' }) {
  const navigate = useNavigate();
  const [status, setStatus] = useState<ItemStatus | ''>('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [list, setList] = useState<List | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = (s = status, q = search, p = page) =>
    api<List>(`/items?type=${type}&status=${s}&search=${encodeURIComponent(q)}&page=${p}`)
      .then(setList, () => setMessage('Could not load the list.'));
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
      setMessage((e as ApiError).status === 403 ? 'You cannot add one.' : 'Could not add one.');
    }
  }

  async function act(it: ItemSummary, restore: boolean) {
    setMessage(null);
    try {
      await api(restore ? `/items/${it.id}/restore` : `/items/${it.id}`, { method: restore ? 'POST' : 'DELETE' });
      load();
    } catch (e) {
      const err = e as ApiError;
      setMessage(err.error === 'csrf' ? err.message! : err.status === 403 ? 'You cannot change this one.' : 'Could not save the change.');
    }
  }

  const counts = list?.counts ?? {};
  const all = Object.entries(counts).filter(([s]) => s !== 'trash').reduce((n, [, c]) => n + c, 0);
  const pages = list ? Math.max(1, Math.ceil(list.total / PER_PAGE)) : 1;
  const noun = type === 'page' ? 'page' : 'post';

  return (
    <Shell>
      <div className="heading-row">
        <h1>{type === 'page' ? 'Pages' : 'Posts'}</h1>
        <button type="button" className="primary" onClick={add}>Add new {noun}</button>
      </div>
      {message && <p role="alert" className="error">{message}</p>}
      <nav aria-label="Filter by status" className="tabs">
        {TABS.filter((t) => t.status === '' || t.status === status || counts[t.status]).map((t) => (
          <button key={t.status} type="button" aria-pressed={status === t.status} onClick={() => show(t.status)}>
            {t.label} ({t.status === '' ? all : counts[t.status] ?? 0})
          </button>
        ))}
      </nav>
      <form role="search" onSubmit={(e: FormEvent) => { e.preventDefault(); setPage(1); load(status, search, 1); }}>
        <Field label={`Search ${noun}s`} name="search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
      </form>
      {list && (
        <>
          <p className="muted" role="status">{list.total} {noun}{list.total === 1 ? '' : 's'}</p>
          <table className="table">
            <thead><tr><th>Title</th><th>Author</th><th>Status</th><th>Date</th><th /></tr></thead>
            <tbody>
              {list.items.map((it) => (
                <tr key={it.id}>
                  <td>{it.status === 'trash' ? (it.title || '(no title)') : <Link to={`/edit/${it.id}`}>{it.title || '(no title)'}</Link>}</td>
                  <td>{it.author ?? '—'}</td>
                  <td>{LABEL[it.status] ?? it.status}</td>
                  <td>{(it.date ?? it.modified)?.slice(0, 16).replace('T', ' ')}</td>
                  <td>
                    {it.status === 'trash'
                      ? <button type="button" onClick={() => act(it, true)} aria-label={`Restore ${it.title || 'untitled'}`}>Restore</button>
                      : <button type="button" onClick={() => act(it, false)} aria-label={`Move ${it.title || 'untitled'} to the trash`}>Trash</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {pages > 1 && (
            <nav aria-label="Pages">
              <button type="button" disabled={page <= 1} onClick={() => show(status, page - 1)}>Previous</button>{' '}
              <span>Page {page} of {pages}</span>{' '}
              <button type="button" disabled={page >= pages} onClick={() => show(status, page + 1)}>Next</button>
            </nav>
          )}
        </>
      )}
    </Shell>
  );
}
