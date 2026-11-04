import { type FormEvent, useEffect, useState } from 'react';
import { __, _n, sprintf } from '@wordpress/i18n';
import { type ApiError, type RedirectView, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';

const PER_PAGE = 50;
const EMPTY: RedirectView = { from: '', to: '', status: 301 };
const messages = (): Record<string, string> => ({
  from: __('Start with / (a path) or ? (a query such as ?p=12), without .. segments.', 'stillpress'),
  to: __('Use a path on this site starting with /, or a full http(s) address, different from the old address.', 'stillpress'),
  status: __('Choose 301 or 302.', 'stillpress'),
});

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
      .then(setList, () => setMessage(__('Could not load the redirects.', 'stillpress')));
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
      if (f.error === 'shadows_item') setErrors({ from: __('A published post or page lives at this address.', 'stillpress') });
      else if (f.field) setErrors({ [f.field]: messages()[f.field] ?? __('Check this field.', 'stillpress') });
      else setMessage(f.error === 'csrf' ? f.message! : __('Could not add the redirect.', 'stillpress'));
    }
  }

  async function remove(r: RedirectView) {
    setMessage(null);
    try {
      await api(`/redirects?from=${encodeURIComponent(r.from)}`, { method: 'DELETE' });
      load();
    } catch (err) {
      const f = err as ApiError;
      setMessage(f.error === 'csrf' ? f.message! : __('Could not delete the redirect.', 'stillpress'));
    }
  }

  const pages = list ? Math.max(1, Math.ceil(list.total / PER_PAGE)) : 1;

  return (
    <Shell>
      <h1>{__('Redirects', 'stillpress')}</h1>
      {message && <p role="alert" className="error">{message}</p>}
      <form role="search" onSubmit={(e) => { e.preventDefault(); setPage(1); load(search, 1); }}>
        <Field label={__('Search redirects', 'stillpress')} name="search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} />
      </form>
      {list && (
        <>
          <p className="muted" role="status">{sprintf(_n('%d redirect', '%d redirects', list.total, 'stillpress'), list.total)}</p>
          <table className="table">
            <thead><tr><th>{__('From', 'stillpress')}</th><th>{__('To', 'stillpress')}</th><th>{__('Status', 'stillpress')}</th><th /></tr></thead>
            <tbody>
              {list.items.map((r) => (
                <tr key={r.from}>
                  <td><code>{r.from}</code></td>
                  <td><code>{r.to}</code></td>
                  <td>{r.status}</td>
                  <td><button type="button" aria-label={sprintf(__('Delete redirect from %s', 'stillpress'), r.from)} onClick={() => remove(r)}>{__('Delete', 'stillpress')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          {pages > 1 && (
            <nav aria-label={__('Pagination', 'stillpress')}>
              <button type="button" disabled={page <= 1} onClick={() => { setPage(page - 1); load(search, page - 1); }}>{__('Previous', 'stillpress')}</button>{' '}
              <span>{sprintf(__('Page %1$d of %2$d', 'stillpress'), page, pages)}</span>{' '}
              <button type="button" disabled={page >= pages} onClick={() => { setPage(page + 1); load(search, page + 1); }}>{__('Next', 'stillpress')}</button>
            </nav>
          )}
        </>
      )}
      <h2>{__('Add redirect', 'stillpress')}</h2>
      <form onSubmit={add}>
        <Field label={__('Old address', 'stillpress')} name="from" required value={form.from} error={errors.from}
          hint={__('A decoded path such as /old-page/, or a query such as ?p=12.', 'stillpress')} onChange={(e) => setForm({ ...form, from: e.target.value })} />
        <Field label={__('New address', 'stillpress')} name="to" required value={form.to} error={errors.to}
          onChange={(e) => setForm({ ...form, to: e.target.value })} />
        <div className="field">
          <label htmlFor="status">{__('Type', 'stillpress')}</label>
          <select id="status" value={form.status} onChange={(e) => setForm({ ...form, status: Number(e.target.value) as 301 | 302 })}>
            <option value={301}>{__('301 (moved permanently)', 'stillpress')}</option>
            <option value={302}>{__('302 (temporary)', 'stillpress')}</option>
          </select>
        </div>
        <button type="submit" className="primary">{__('Add redirect', 'stillpress')}</button>
      </form>
    </Shell>
  );
}
