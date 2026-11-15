import { type FormEvent, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { __ } from '@wordpress/i18n';
import { asApiError, type SecondStep, type SessionUser, api } from '../api.ts';
import { passkeyAssertion, passkeysSupported } from '../webauthn.ts';
import { loadLocale } from '../i18n/index.ts';
import { Field } from '../Field.tsx';
import { CAPTCHA_FAILED, useCaptcha } from '../Captcha.tsx';

export default function Login() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const captcha = useCaptcha();
  // set once the password is accepted and a second factor is still owed
  const [step, setStep] = useState<SecondStep | undefined>();
  const [code, setCode] = useState('');

  async function finish() {
    // the user's own language from here on; the login page itself followed the browser's
    await loadLocale((await api<SessionUser>('/session')).locale);
    const next = params.get('next');
    // only a path inside the admin; anything else could leave the site
    void navigate(next?.startsWith('/') && !next.startsWith('//') ? next : '/', { replace: true });
  }

  /** Runs one sign-in call and finishes; a cancelled browser prompt shows nothing. */
  async function attempt(call: () => Promise<unknown>) {
    setBusy(true);
    setError(undefined);
    try {
      const r = await call();
      if (r) setStep(r as SecondStep);
      else await finish();
    } catch (e) {
      if ((e as { name?: string })?.name === 'NotAllowedError') return;
      const err = asApiError(e);
      captcha.failed(err);
      if (err.error === 'second_step_expired') {
        setStep(undefined);
        setCode('');
        setError(__('Your sign-in timed out. Enter your password again.', 'stillpress'));
        return;
      }
      setError(err.error === 'captcha_failed' ? CAPTCHA_FAILED() : err.error === 'csrf' ? err.message : err.status === 429 ? __('Too many attempts. Wait a few minutes and try again.', 'stillpress')
        : err.error === 'invalid_code' ? __('The code is incorrect.', 'stillpress')
        : err.error === 'invalid_passkey' ? __('The passkey was not accepted.', 'stillpress')
        : err.status === 401 ? __('The login or password is incorrect.', 'stillpress') : __('Login failed. Try again.', 'stillpress'));
    } finally {
      setBusy(false);
    }
  }

  const submit = (e: FormEvent) => {
    e.preventDefault();
    void attempt(() => api<SecondStep | undefined>('/login', { method: 'POST', body: { login, password, ...captcha.fields } }));
  };
  const submitCode = (e: FormEvent) => {
    e.preventDefault();
    void attempt(() => api('/login/totp', { method: 'POST', body: { code } }));
  };
  const withPasskey = (path: string) => attempt(async () => api(path, { method: 'POST', body: await passkeyAssertion() }));

  if (step) {
    return (
      <main className="page narrow">
        <h1>{__('Confirm it is you', 'stillpress')}</h1>
        {step.totp && (
          <form onSubmit={submitCode} noValidate>
            <Field label={__('Code', 'stillpress')} name="code" inputMode="numeric" autoComplete="one-time-code" maxLength={6}
              required value={code} onChange={(e) => setCode(e.target.value)} error={error}
              hint={__('The 6-digit code from your authenticator app.', 'stillpress')} />
            <button type="submit" className="primary" disabled={busy}>{__('Confirm', 'stillpress')}</button>
          </form>
        )}
        {!step.totp && error && <p role="alert" className="error">{error}</p>}
        {step.passkey && (
          <p><button type="button" disabled={busy} onClick={() => void withPasskey('/login/passkey-step')}>{__('Use a passkey', 'stillpress')}</button></p>
        )}
      </main>
    );
  }

  return (
    <main className="page narrow">
      <h1>{__('Log in', 'stillpress')}</h1>
      <form onSubmit={submit} noValidate>
        <Field label={__('Login or email', 'stillpress')} name="login" autoComplete="username" required value={login}
          onChange={(e) => setLogin(e.target.value)} />
        <Field label={__('Password', 'stillpress')} name="password" type="password" autoComplete="current-password" required
          value={password} onChange={(e) => setPassword(e.target.value)} error={error} />
        {captcha.widgets}
        <button type="submit" className="primary" disabled={busy || !captcha.ready}>{busy ? __('Logging in…', 'stillpress') : __('Log in', 'stillpress')}</button>
      </form>
      {passkeysSupported() && (
        <p><button type="button" disabled={busy} onClick={() => void withPasskey('/login/passkey')}>{__('Sign in with passkey', 'stillpress')}</button></p>
      )}
      <p><Link to="/forgot-password">{__('Forgot password?', 'stillpress')}</Link></p>
    </main>
  );
}
