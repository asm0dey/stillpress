import { type FormEvent, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { __ } from '@wordpress/i18n';
import { asApiError, type ApiError, type SessionUser, api } from '../api.ts';
import { loadLocale } from '../i18n/index.ts';
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
      // the user's own language from here on; the login page itself followed the browser's
      await loadLocale((await api<SessionUser>('/session')).locale);
      const next = params.get('next');
      // only a path inside the admin; anything else could leave the site
      void navigate(next?.startsWith('/') && !next.startsWith('//') ? next : '/', { replace: true });
    } catch (e) {
      const err = asApiError(e);
      setError(err.error === 'csrf' ? err.message : err.status === 429 ? __('Too many attempts. Wait a few minutes and try again.', 'stillpress')
        : err.status === 401 ? __('The login or password is incorrect.', 'stillpress') : __('Login failed. Try again.', 'stillpress'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page narrow">
      <h1>{__('Log in', 'stillpress')}</h1>
      <form onSubmit={submit} noValidate>
        <Field label={__('Login or email', 'stillpress')} name="login" autoComplete="username" required value={login}
          onChange={(e) => setLogin(e.target.value)} />
        <Field label={__('Password', 'stillpress')} name="password" type="password" autoComplete="current-password" required
          value={password} onChange={(e) => setPassword(e.target.value)} error={error} />
        <button type="submit" className="primary" disabled={busy}>{busy ? __('Logging in…', 'stillpress') : __('Log in', 'stillpress')}</button>
      </form>
    </main>
  );
}
