import { type FormEvent, useEffect, useState } from 'react';
import { type ApiError, type RedirectView, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';

const PER_PAGE = 50;
const EMPTY: RedirectView = { from: '', to: '', status: 301 };
const MESSAGES: Record<string, string> = {
  from: 'Start with / (a path) or ? (a query such as ?p=12), without .. segments.',
  to: 'Use a path on this site starting with /, or a full http(s) address, different from the old address.',
  status: 'Choose 301 or 302.',
};

/** The redirect map: searchable, with add and delete. Served by Stillpress, so changes apply at once. */
export default function Redirects() {
  const [list, setList] = useState<{ items: RedirectView[]; total: number } | null>(null);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [form, setForm] = useState(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);

  const load = (q = search, p = page) =>
    api<{ items: RedirectView[]; total: number }>(`/redirects?search=${encodeURIComponent(q)}&page=${p}`)
      .then(setList, () => setMessage('Could not load the redirects.'));
  useEffect(() => { load(); }, []);

  async function add(e: FormEvent) {
    e.preventDefault();
    setErrors({});
    setMessage(null);
    try {
      await api('/redirects', { method: 'POST', body: form });
      setForm(EMPTY);
      load();
    } catch (err) {
      const f = err as ApiError;
      if (f.error === 'shadows_item') setErrors({ from: 'A published post or page lives at this address.' });
      else if (f.field) setErrors({ [f.field]: MESSAGES[f.field] ?? 'Check this field.' });
      else setMessage(f.error === 'csrf' ? f.message! : 'Could not add the redirect.');
    }
  }

  async function remove(r: RedirectView) {
    setMessage(null);
    try {
      await api(`/redirects?from=${encodeURIComponent(r.from)}`, { method: 'DELETE' });
      load();
    } catch (err) {
      const f = err as ApiError;
      setMessage(f.error === 'csrf' ? f.message! : 'Could not delete the redirect.');
    }
  }

  const pages = list ? Math.max(1, Math.ceil(list.total / PER_PAGE)) : 1;

  return (
    <Shell>
      <h1>Redirects</h1>
      {message && <p role="alert" className="error">{message}</p>}
      <form role="search" onSubmit={(e) => { e.preventDefault(); setPage(1); load(search, 1); }}>
        <Field label="Search redirects" name="search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
      </form>
      {list && (
        <>
          <p className="muted" role="status">{list.total} redirect{list.total === 1 ? '' : 's'}</p>
          <table className="table">
            <thead><tr><th>From</th><th>To</th><th>Status</th><th /></tr></thead>
            <tbody>
              {list.items.map((r) => (
                <tr key={r.from}>
                  <td><code>{r.from}</code></td>
                  <td><code>{r.to}</code></td>
                  <td>{r.status}</td>
                  <td><button type="button" aria-label={`Delete redirect from ${r.from}`} onClick={() => remove(r)}>Delete</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          {pages > 1 && (
            <nav aria-label="Pages">
              <button type="button" disabled={page <= 1} onClick={() => { setPage(page - 1); load(search, page - 1); }}>Previous</button>{' '}
              <span>Page {page} of {pages}</span>{' '}
              <button type="button" disabled={page >= pages} onClick={() => { setPage(page + 1); load(search, page + 1); }}>Next</button>
            </nav>
          )}
        </>
      )}
      <h2>Add redirect</h2>
      <form onSubmit={add}>
        <Field label="Old address" name="from" required value={form.from} error={errors.from}
          hint="A decoded path such as /old-page/, or a query such as ?p=12." onChange={(e) => setForm({ ...form, from: e.target.value })} />
        <Field label="New address" name="to" required value={form.to} error={errors.to}
          onChange={(e) => setForm({ ...form, to: e.target.value })} />
        <div className="field">
          <label htmlFor="status">Type</label>
          <select id="status" value={form.status} onChange={(e) => setForm({ ...form, status: Number(e.target.value) as 301 | 302 })}>
            <option value={301}>301 (moved permanently)</option>
            <option value={302}>302 (temporary)</option>
          </select>
        </div>
        <button type="submit" className="primary">Add redirect</button>
      </form>
    </Shell>
  );
}
