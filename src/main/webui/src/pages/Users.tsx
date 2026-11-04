import { type ChangeEvent, type FormEvent, useEffect, useState } from 'react';
import { type ApiError, type Role, type UserView, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';

interface NewUser { login: string; email: string; displayName: string; role: Role }

const ROLES: Role[] = ['administrator', 'editor', 'author', 'contributor'];
const EMPTY: NewUser = { login: '', email: '', displayName: '', role: 'author' };

export default function Users() {
  const [users, setUsers] = useState<UserView[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [link, setLink] = useState<{ login: string; url: string } | null>(null);
  const [form, setForm] = useState(EMPTY);
  const [editing, setEditing] = useState<{ id: number; displayName: string; email: string } | null>(null);
  const [editErrors, setEditErrors] = useState<Record<string, string>>({});

  const load = () => api<UserView[]>('/users').then(setUsers, () => setMessage('Could not load the users.'));
  useEffect(() => { load(); }, []);

  async function changeRole(u: UserView, role: Role) {
    setMessage(null);
    try {
      await api(`/users/${u.id}/role`, { method: 'PUT', body: { role } });
    } catch (e) {
      const err = e as ApiError;
      setMessage(err.error === 'csrf' ? err.message! : err.error === 'last_administrator'
        ? 'There must be at least one administrator with a password.' : 'Could not change the role.');
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
      const err = e as ApiError;
      if (err.error === 'email_taken') setEditErrors({ email: 'Another user has this email.' });
      else if (err.field === 'email') setEditErrors({ email: 'Use an email address of at most 100 characters.' });
      else if (err.field === 'displayName') setEditErrors({ displayName: 'Enter a name of at most 250 characters.' });
      else setMessage(err.error === 'csrf' ? err.message! : 'Could not save the profile.');
    }
  }

  async function showLink(u: UserView) {
    setMessage(null);
    try {
      const { url } = await api<{ url: string }>(`/users/${u.id}/password-link`, { method: 'POST' });
      await copy(u.login, url);
    } catch (e) {
      const err = e as ApiError;
      setMessage(err.error === 'csrf' ? err.message! : 'Could not create the link.');
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
    try {
      const { setPasswordUrl } = await api<{ setPasswordUrl: string }>('/users', { method: 'POST', body: form });
      await copy(form.login, setPasswordUrl);
      setForm(EMPTY);
      load();
    } catch (e) {
      const err = e as ApiError;
      setMessage(err.error === 'csrf' ? err.message! : err.error === 'login_taken' ? 'That login is already taken.' : 'Check the fields and try again.');
    }
  }

  const set = (k: keyof NewUser) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setForm({ ...form, [k]: e.target.value });

  return (
    <Shell>
      <h1>Users</h1>
      {message && <p role="alert" className="error">{message}</p>}
      {link && (
        <div className="link-box" role="status">
          Set-password link for {link.login} (valid 24 hours, single use; copied if your browser allowed it):
          <br /><code>{link.url}</code>
        </div>
      )}
      {users && (
        <table className="users">
          <thead><tr><th>Login</th><th>Name</th><th>Email</th><th>Role</th><th /></tr></thead>
          <tbody>
            {users.map((u) => editing?.id === u.id ? (
              <tr key={u.id}>
                <td>{u.login}</td>
                <td colSpan={4}>
                  <form onSubmit={saveProfile}>
                    <Field label="Display name" name="edit-displayName" required value={editing.displayName} error={editErrors.displayName}
                      hint="The author archive address stays the same." onChange={(e) => setEditing({ ...editing, displayName: e.target.value })} />
                    <Field label="Email" name="edit-email" type="email" required value={editing.email} error={editErrors.email}
                      onChange={(e) => setEditing({ ...editing, email: e.target.value })} />
                    <button type="submit" className="primary">Save</button>{' '}
                    <button type="button" onClick={() => setEditing(null)}>Cancel</button>
                  </form>
                </td>
              </tr>
            ) : (
              <tr key={u.id}>
                <td>{u.login}</td>
                <td>{u.displayName}</td>
                <td>{u.email}</td>
                <td>
                  <select aria-label={`Role of ${u.login}`} value={u.role} onChange={(e) => changeRole(u, e.target.value as Role)}>
                    {ROLES.map((r) => <option key={r}>{r}</option>)}
                  </select>
                </td>
                <td>
                  <button type="button" onClick={() => { setEditErrors({}); setEditing({ id: u.id, displayName: u.displayName ?? '', email: u.email ?? '' }); }}>Edit</button>{' '}
                  <button type="button" onClick={() => showLink(u)}>Copy set-password link</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h2>Add user</h2>
      <form onSubmit={add}>
        <Field label="Login" name="login" required value={form.login} onChange={set('login')} />
        <Field label="Email" name="email" type="email" required value={form.email} onChange={set('email')} />
        <Field label="Display name" name="displayName" required value={form.displayName} onChange={set('displayName')} />
        <div className="field">
          <label htmlFor="new-role">Role</label>
          <select id="new-role" value={form.role} onChange={set('role')}>
            {ROLES.map((r) => <option key={r}>{r}</option>)}
          </select>
        </div>
        <button type="submit" className="primary">Add user</button>
      </form>
    </Shell>
  );
}
