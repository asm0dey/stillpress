import { type ChangeEvent, type FormEvent, useEffect, useState } from 'react';
import { __, sprintf } from '@wordpress/i18n';
import { asApiError, type ApiError, type Role, type UserView, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';
import { useLatest } from '../useLatest.ts';

interface NewUser { login: string; email: string; displayName: string; role: Role }

const ROLES: Role[] = ['administrator', 'editor', 'author', 'contributor'];
/** A role's name, as WordPress shows it. */
const roleLabel = (r: Role): string => ({
  administrator: __('Administrator', 'stillpress'), editor: __('Editor', 'stillpress'), author: __('Author', 'stillpress'),
  contributor: __('Contributor', 'stillpress'),
})[r];
const EMPTY: NewUser = { login: '', email: '', displayName: '', role: 'author' };

export default function Users() {
  const [users, setUsers] = useState<UserView[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [link, setLink] = useState<{ login: string; url: string } | null>(null);
  const [form, setForm] = useState(EMPTY);
  const [editing, setEditing] = useState<{ id: number; displayName: string; email: string } | null>(null);
  const [editErrors, setEditErrors] = useState<Record<string, string>>({});
  const [addErrors, setAddErrors] = useState<Record<string, string>>({});

  const latest = useLatest();
  const load = () => latest(api<UserView[]>('/users')).then(setUsers, () => setMessage(__('Could not load the users.', 'stillpress')));
  useEffect(() => { load(); }, []);

  async function changeRole(u: UserView, role: Role) {
    setMessage(null);
    try {
      await api(`/users/${u.id}/role`, { method: 'PUT', body: { role } });
    } catch (e) {
      const err = asApiError(e);
      setMessage(err.error === 'csrf' ? err.message! : err.error === 'last_administrator'
        ? __('There must be at least one administrator with a password.', 'stillpress') : __('Could not change the role.', 'stillpress'));
    }
    load();
  }

  async function saveProfile(e: FormEvent) {
    e.preventDefault();
    if (!editing) return;
    setMessage(null);
    setEditErrors({});
    try {
      await api(`/users/${editing.id}`, { method: 'PUT', body: { displayName: editing.displayName, email: editing.email } });
      setEditing(null);
      load();
    } catch (e) {
      const err = asApiError(e);
      if (err.error === 'email_taken') setEditErrors({ email: __('Another user has this email.', 'stillpress') });
      else if (err.field === 'email') setEditErrors({ email: __('Use an email address of at most 100 characters.', 'stillpress') });
      else if (err.field === 'displayName') setEditErrors({ displayName: __('Enter a name of at most 250 characters.', 'stillpress') });
      else setMessage(err.error === 'csrf' ? err.message! : __('Could not save the profile.', 'stillpress'));
    }
  }

  async function showLink(u: UserView) {
    setMessage(null);
    try {
      const { url } = await api<{ url: string }>(`/users/${u.id}/password-link`, { method: 'POST' });
      await copy(u.login, url);
    } catch (e) {
      const err = asApiError(e);
      setMessage(err.error === 'csrf' ? err.message! : __('Could not create the link.', 'stillpress'));
    }
  }

  async function copy(login: string, url: string) {
    // the link is shown either way: clipboard access can be refused
    setLink({ login, url: new URL(url, location.origin).href });
    await navigator.clipboard?.writeText(new URL(url, location.origin).href).catch(() => {});
  }

  async function add(e: FormEvent) {
    e.preventDefault();
    setMessage(null);
    setAddErrors({});
    try {
      const { setPasswordUrl } = await api<{ setPasswordUrl: string }>('/users', { method: 'POST', body: form });
      await copy(form.login, setPasswordUrl);
      setForm(EMPTY);
      load();
    } catch (e) {
      const err = asApiError(e);
      if (err.error === 'login_taken') setAddErrors({ login: __('That login is already taken.', 'stillpress') });
      else if (err.error === 'email_taken') setAddErrors({ email: __('Another user has this email.', 'stillpress') });
      else if (err.field === 'login') setAddErrors({ login: __('Use up to 60 letters, digits, spaces and the characters _ . - @', 'stillpress') });
      else if (err.field === 'email') setAddErrors({ email: __('Use an email address of at most 100 characters.', 'stillpress') });
      else if (err.field === 'displayName') setAddErrors({ displayName: __('Enter a name of at most 250 characters.', 'stillpress') });
      else setMessage(err.error === 'csrf' ? err.message! : __('Check the fields and try again.', 'stillpress'));
    }
  }

  const set = (k: keyof NewUser) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setForm({ ...form, [k]: e.target.value });

  return (
    <Shell>
      <h1>{__('Users', 'stillpress')}</h1>
      {message && <p role="alert" className="error">{message}</p>}
      {link && (
        <div className="link-box" role="status">
          {sprintf(__('Set-password link for %s (valid 24 hours, single use; copied if your browser allowed it):', 'stillpress'), link.login)}
          <br /><code>{link.url}</code>
        </div>
      )}
      {users && (
        <table className="users">
          <thead><tr><th>{__('Login', 'stillpress')}</th><th>{__('Name', 'stillpress')}</th><th>{__('Email', 'stillpress')}</th><th>{__('Role', 'stillpress')}</th><th /></tr></thead>
          <tbody>
            {users.map((u) => editing?.id === u.id ? (
              <tr key={u.id}>
                <td>{u.login}</td>
                <td colSpan={4}>
                  <form onSubmit={saveProfile}>
                    <Field label={__('Display name', 'stillpress')} name="edit-displayName" required value={editing.displayName} error={editErrors.displayName}
                      hint={__('The author archive address stays the same.', 'stillpress')} onChange={(e) => setEditing({ ...editing, displayName: e.target.value })} />
                    <Field label={__('Email', 'stillpress')} name="edit-email" type="email" required value={editing.email} error={editErrors.email}
                      onChange={(e) => setEditing({ ...editing, email: e.target.value })} />
                    <button type="submit" className="primary">{__('Save', 'stillpress')}</button>{' '}
                    <button type="button" onClick={() => setEditing(null)}>{__('Cancel', 'stillpress')}</button>
                  </form>
                </td>
              </tr>
            ) : (
              <tr key={u.id}>
                <td>{u.login}</td>
                <td>{u.displayName}</td>
                <td>{u.email}</td>
                <td>
                  <select aria-label={sprintf(__('Role of %s', 'stillpress'), u.login)} value={u.role} onChange={(e) => changeRole(u, e.target.value as Role)}>
                    {ROLES.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}
                  </select>
                </td>
                <td>
                  <button type="button" onClick={() => { setEditErrors({}); setEditing({ id: u.id, displayName: u.displayName ?? '', email: u.email ?? '' }); }}>{__('Edit', 'stillpress')}</button>{' '}
                  <button type="button" onClick={() => showLink(u)}>{__('Copy set-password link', 'stillpress')}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h2>{__('Add user', 'stillpress')}</h2>
      <form onSubmit={add}>
        <Field label={__('Login', 'stillpress')} name="login" required value={form.login} error={addErrors.login} onChange={set('login')} />
        <Field label={__('Email', 'stillpress')} name="email" type="email" required value={form.email} error={addErrors.email} onChange={set('email')} />
        <Field label={__('Display name', 'stillpress')} name="displayName" required value={form.displayName} error={addErrors.displayName} onChange={set('displayName')} />
        <div className="field">
          <label htmlFor="new-role">{__('Role', 'stillpress')}</label>
          <select id="new-role" value={form.role} onChange={set('role')}>
            {ROLES.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}
          </select>
        </div>
        <button type="submit" className="primary">{__('Add user', 'stillpress')}</button>
      </form>
    </Shell>
  );
}
