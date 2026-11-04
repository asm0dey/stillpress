import { type FormEvent, useEffect, useState } from 'react';
import { __, sprintf } from '@wordpress/i18n';
import { asApiError, type ApiError, type TermView, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';
import { useLatest } from '../useLatest.ts';

type Taxonomy = 'category' | 'post_tag';

interface Draft { name: string; slug: string; parentId: number | null; description: string }

const messages = (): Record<string, string> => ({
  name: __('Enter a name.', 'stillpress'),
  slug: __('This slug is already used, or has no letters or digits.', 'stillpress'),
  parentId: __('A category cannot sit under itself or one of its own subcategories.', 'stillpress'),
});

/** Categories as an indented tree with a parent select, tags as a flat list; shared by both screens. */
export default function Terms({ taxonomy }: { taxonomy: Taxonomy }) {
  const category = taxonomy === 'category';
  const [terms, setTerms] = useState<TermView[] | null>(null);
  const [editing, setEditing] = useState<number | null>(null);
  const [draft, setDraft] = useState<Draft>({ name: '', slug: '', parentId: null, description: '' });
  const [newName, setNewName] = useState('');
  const [newParent, setNewParent] = useState<number | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);

  const latest = useLatest();
  const load = () => latest(api<TermView[]>(`/terms?taxonomy=${taxonomy}`))
    .then(setTerms, () => setMessage(__('Could not load the list.', 'stillpress')));
  useEffect(() => { setEditing(null); void load(); }, [taxonomy]);

  function fail(e: unknown) {
    const err = asApiError(e);
    if (err.error === 'slug_taken') setErrors({ slug: messages().slug });
    else if (err.field) setErrors({ [err.field]: messages()[err.field] ?? __('Check this field.', 'stillpress') });
    else setMessage(err.error === 'csrf' ? err.message! : err.error === 'default_category'
      ? __('The default category cannot be deleted. Choose another one in Settings first.', 'stillpress')
      : err.status === 403 ? __('Only editors and administrators can change this.', 'stillpress') : __('Could not save the change.', 'stillpress'));
  }

  function edit(t: TermView) {
    setErrors({});
    setMessage(null);
    setEditing(t.id);
    setDraft({ name: t.name, slug: t.slug, parentId: t.parentId, description: t.description ?? '' });
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    setErrors({});
    setMessage(null);
    try {
      await api(`/terms/${editing}`, { method: 'PUT', body: draft });
      setEditing(null);
      void load();
    } catch (err) { fail(err); }
  }

  async function remove(t: TermView) {
    if (!confirm(sprintf(__('Delete “%s”? Its posts lose it; addresses under it stop working.', 'stillpress'), t.name))) return;
    setMessage(null);
    try {
      await api(`/terms/${t.id}`, { method: 'DELETE' });
      void load();
    } catch (err) { fail(err); }
  }

  async function add(e: FormEvent) {
    e.preventDefault();
    setErrors({});
    setMessage(null);
    try {
      await api('/terms', { method: 'POST', body: { taxonomy, name: newName, parentId: newParent } });
      setNewName('');
      setNewParent(null);
      void load();
    } catch (err) { fail(err); }
  }

  // parents before children, each child indented under its parent
  const rows: { term: TermView; depth: number }[] = [];
  const walk = (parent: number | null, depth: number, seen: Set<number>) => {
    for (const t of (terms ?? []).filter((x) => (category ? x.parentId : null) === parent)) {
      if (seen.has(t.id)) continue;
      seen.add(t.id);
      rows.push({ term: t, depth });
      if (category) walk(t.id, depth + 1, seen);
    }
  };
  walk(null, 0, new Set());

  const parentSelect = (id: string, value: number | null, onChange: (v: number | null) => void, self?: number) => (
    <div className="field">
      <label htmlFor={id}>{__('Parent', 'stillpress')}</label>
      <select id={id} value={value ?? ''} aria-invalid={errors.parentId ? 'true' : undefined}
        aria-describedby={errors.parentId ? id + '-error' : undefined}
        onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}>
        <option value="">{__('None', 'stillpress')}</option>
        {rows.filter((r) => r.term.id !== self).map((r) => (
          <option key={r.term.id} value={r.term.id}>{'— '.repeat(r.depth) + r.term.name}</option>
        ))}
      </select>
      {errors.parentId && <p id={id + '-error'} className="error">{errors.parentId}</p>}
    </div>
  );

  return (
    <Shell>
      <h1>{category ? __('Categories', 'stillpress') : __('Tags', 'stillpress')}</h1>
      {message && <p role="alert" className="error">{message}</p>}
      {terms && (
        <table className="table">
          <thead><tr><th>{__('Name', 'stillpress')}</th><th>{__('Slug', 'stillpress')}</th><th>{__('Posts', 'stillpress')}</th><th /></tr></thead>
          <tbody>
            {rows.map(({ term: t, depth }) => editing === t.id ? (
              <tr key={t.id}>
                <td colSpan={4}>
                  <form onSubmit={save}>
                    <Field label={__('Name', 'stillpress')} name="name" required value={draft.name} error={errors.name}
                      onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
                    <Field label={__('Slug', 'stillpress')} name="slug" value={draft.slug} error={errors.slug}
                      hint={__('Changing it moves the archive; the old address redirects to the new one.', 'stillpress')}
                      onChange={(e) => setDraft({ ...draft, slug: e.target.value })} />
                    {category && parentSelect('edit-parent', draft.parentId, (v) => setDraft({ ...draft, parentId: v }), t.id)}
                    <Field label={__('Description', 'stillpress')} name="description" value={draft.description}
                      onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
                    <button type="submit" className="primary">{__('Save', 'stillpress')}</button>{' '}
                    <button type="button" onClick={() => setEditing(null)}>{__('Cancel', 'stillpress')}</button>
                  </form>
                </td>
              </tr>
            ) : (
              <tr key={t.id}>
                <td style={{ paddingLeft: `calc(var(--space-2) + ${depth} * var(--space-4))` }}>{t.name}</td>
                <td>{t.slug}</td>
                <td>{t.count ?? 0}</td>
                <td>
                  <button type="button" onClick={() => edit(t)}>{__('Edit', 'stillpress')}</button>{' '}
                  <button type="button" onClick={() => remove(t)}>{__('Delete', 'stillpress')}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h2>{category ? __('Add category', 'stillpress') : __('Add tag', 'stillpress')}</h2>
      <form onSubmit={add}>
        <Field label={__('Name', 'stillpress')} name="new-name" required value={newName} error={editing === null ? errors.name : undefined}
          onChange={(e) => setNewName(e.target.value)} />
        {category && parentSelect('new-parent', newParent, setNewParent)}
        <button type="submit" className="primary">{category ? __('Add category', 'stillpress') : __('Add tag', 'stillpress')}</button>
      </form>
    </Shell>
  );
}
