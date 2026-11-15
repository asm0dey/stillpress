import { type FormEvent, useState } from 'react';
import { Link } from 'react-router';
import { __ } from '@wordpress/i18n';
import { asApiError, api } from '../api.ts';
import { Field } from '../Field.tsx';
import { CAPTCHA_FAILED, useCaptcha } from '../Captcha.tsx';

/** Asks for a password-reset mail; the answer is the same whether or not the account exists. */
export default function ForgotPassword() {
  const [login, setLogin] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);

  const captcha = useCaptcha();
  const mail = captcha.options?.mail;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      await api('/password-reset', { method: 'POST', body: { login, ...captcha.fields } });
      setSent(true);
    } catch (e) {
      const err = asApiError(e);
      captcha.reset();
      setError(err.error === 'captcha_failed' ? CAPTCHA_FAILED() : err.error === 'csrf' ? err.message : err.status === 429 ? __('Too many attempts. Wait a few minutes and try again.', 'stillpress')
        : __('Could not send the link. Try again.', 'stillpress'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page narrow">
      <h1>{__('Forgot password', 'stillpress')}</h1>
      {mail === false && <p>{__("Email isn't set up on this site. Ask an administrator for a link to set your password.", 'stillpress')}</p>}
      {sent && <p role="status">{__('If an account matches, we sent a link to its email address. The link works for one hour.', 'stillpress')}</p>}
      {mail && !sent && (
        <form onSubmit={submit} noValidate>
          <Field label={__('Login or email', 'stillpress')} name="login" autoComplete="username" required value={login}
            onChange={(e) => setLogin(e.target.value)} error={error} />
          {captcha.widgets}
          <button type="submit" className="primary" disabled={busy || !captcha.ready}>{__('Send link', 'stillpress')}</button>
        </form>
      )}
      <p><Link to="/login">{__('Back to log in', 'stillpress')}</Link></p>
    </main>
  );
}
