import { type FormEvent, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { type ApiError, api } from '../api.ts';
import { Field } from '../Field.tsx';

export default function Login() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      await api('/login', { method: 'POST', body: { login, password } });
      const next = params.get('next');
      // only a path inside the admin; anything else could leave the site
      navigate(next?.startsWith('/') && !next.startsWith('//') ? next : '/', { replace: true });
    } catch (e) {
      const err = e as ApiError;
      setError(err.error === 'csrf' ? err.message : err.status === 429 ? 'Too many attempts. Wait a few minutes and try again.'
        : err.status === 401 ? 'The login or password is incorrect.' : 'Login failed. Try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page narrow">
      <h1>Log in</h1>
      <form onSubmit={submit} noValidate>
        <Field label="Login or email" name="login" autoComplete="username" required value={login}
          onChange={(e) => setLogin(e.target.value)} />
        <Field label="Password" name="password" type="password" autoComplete="current-password" required
          value={password} onChange={(e) => setPassword(e.target.value)} error={error} />
        <button type="submit" className="primary" disabled={busy}>{busy ? 'Logging in…' : 'Log in'}</button>
      </form>
    </main>
  );
}
