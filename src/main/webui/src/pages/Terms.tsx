import { type FormEvent, useEffect, useState } from 'react';
import { type ApiError, type TermView, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';

type Taxonomy = 'category' | 'post_tag';

interface Draft { name: string; slug: string; parentId: number | null; description: string }

const MESSAGES: Record<string, string> = {
  name: 'Enter a name.',
  slug: 'This slug is already used, or has no letters or digits.',
  parentId: 'A category cannot sit under itself or one of its own subcategories.',
};

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

  const load = () => api<TermView[]>(`/terms?taxonomy=${taxonomy}`).then(setTerms, () => setMessage('Could not load the list.'));
  useEffect(() => { setEditing(null); load(); }, [taxonomy]);

  function fail(e: unknown) {
    const err = e as ApiError;
    if (err.error === 'slug_taken') setErrors({ slug: MESSAGES.slug });
    else if (err.field) setErrors({ [err.field]: MESSAGES[err.field] ?? 'Check this field.' });
    else setMessage(err.error === 'csrf' ? err.message! : err.error === 'default_category'
      ? 'The default category cannot be deleted. Choose another one in Settings first.'
      : err.status === 403 ? 'Only editors and administrators can change this.' : 'Could not save the change.');
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
      load();
    } catch (err) { fail(err); }
  }

  async function remove(t: TermView) {
    if (!confirm(`Delete "${t.name}"? Its posts lose it; addresses under it stop working.`)) return;
    setMessage(null);
    try {
      await api(`/terms/${t.id}`, { method: 'DELETE' });
      load();
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
      load();
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
      <label htmlFor={id}>Parent</label>
      <select id={id} value={value ?? ''} aria-invalid={errors.parentId ? 'true' : undefined}
        aria-describedby={errors.parentId ? id + '-error' : undefined}
        onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}>
        <option value="">None</option>
        {rows.filter((r) => r.term.id !== self).map((r) => (
          <option key={r.term.id} value={r.term.id}>{'— '.repeat(r.depth) + r.term.name}</option>
        ))}
      </select>
      {errors.parentId && <p id={id + '-error'} className="error">{errors.parentId}</p>}
    </div>
  );

  return (
    <Shell>
      <h1>{category ? 'Categories' : 'Tags'}</h1>
      {message && <p role="alert" className="error">{message}</p>}
      {terms && (
        <table className="table">
          <thead><tr><th>Name</th><th>Slug</th><th>Posts</th><th /></tr></thead>
          <tbody>
            {rows.map(({ term: t, depth }) => editing === t.id ? (
              <tr key={t.id}>
                <td colSpan={4}>
                  <form onSubmit={save}>
                    <Field label="Name" name="name" required value={draft.name} error={errors.name}
                      onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
                    <Field label="Slug" name="slug" value={draft.slug} error={errors.slug}
                      hint="Changing it moves the archive; the old address redirects to the new one."
                      onChange={(e) => setDraft({ ...draft, slug: e.target.value })} />
                    {category && parentSelect('edit-parent', draft.parentId, (v) => setDraft({ ...draft, parentId: v }), t.id)}
                    <Field label="Description" name="description" value={draft.description}
                      onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
                    <button type="submit" className="primary">Save</button>{' '}
                    <button type="button" onClick={() => setEditing(null)}>Cancel</button>
                  </form>
                </td>
              </tr>
            ) : (
              <tr key={t.id}>
                <td style={{ paddingLeft: `calc(var(--space-2) + ${depth} * var(--space-4))` }}>{t.name}</td>
                <td>{t.slug}</td>
                <td>{t.count ?? 0}</td>
                <td>
                  <button type="button" onClick={() => edit(t)}>Edit</button>{' '}
                  <button type="button" onClick={() => remove(t)}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h2>{category ? 'Add category' : 'Add tag'}</h2>
      <form onSubmit={add}>
        <Field label="Name" name="new-name" required value={newName} error={editing === null ? errors.name : undefined}
          onChange={(e) => setNewName(e.target.value)} />
        {category && parentSelect('new-parent', newParent, setNewParent)}
        <button type="submit" className="primary">{category ? 'Add category' : 'Add tag'}</button>
      </form>
    </Shell>
  );
}
