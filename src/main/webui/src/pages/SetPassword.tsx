import { type FormEvent, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { __ } from '@wordpress/i18n';
import { asApiError, type ApiError, CSRF_MESSAGE, api } from '../api.ts';
import { Field } from '../Field.tsx';
import { CAPTCHA_FAILED, useCaptcha } from '../Captcha.tsx';

export default function SetPassword() {
  const [params] = useSearchParams();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<{ password?: string; confirm?: string; captcha?: string }>({});
  const captcha = useCaptcha();
  const [state, setState] = useState<'form' | 'done' | 'expired' | 'failed' | 'csrf' | 'limited'>('form');

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (password !== confirm) {
      setErrors({ confirm: __('The passwords do not match.', 'stillpress') });
      return;
    }
    setErrors({});
    try {
      await api('/set-password', { method: 'POST', body: { token: params.get('token'), password, ...captcha.fields } });
      setState('done');
    } catch (e) {
      const err = asApiError(e);
      captcha.failed(err);
      if (err.error === 'csrf') setState('csrf');
      else if (err.error === 'captcha_failed') setErrors({ captcha: CAPTCHA_FAILED() });
      else if (err.status === 429) setState('limited');
      else if (err.status === 400) setErrors({ password: __('Use 12 to 1024 characters.', 'stillpress') });
      else setState(err.status === 410 ? 'expired' : 'failed');
    }
  }

  if (state === 'done') {
    return <main className="page narrow"><h1>{__('Password set', 'stillpress')}</h1><p><Link to="/login">{__('Log in with your new password', 'stillpress')}</Link></p></main>;
  }
  return (
    <main className="page narrow">
      <h1>{__('Set your password', 'stillpress')}</h1>
      {state === 'expired' && <p role="alert" className="error">{__('This link is invalid or has expired. Ask an administrator for a new one.', 'stillpress')}</p>}
      {state === 'csrf' && <p role="alert" className="error">{CSRF_MESSAGE()}</p>}
      {state === 'limited' && <p role="alert" className="error">{__('Too many attempts. Wait a few minutes and try again.', 'stillpress')}</p>}
      {state === 'failed' && <p role="alert" className="error">{__('Could not set the password. Try again.', 'stillpress')}</p>}
      <form onSubmit={submit} noValidate>
        <Field label={__('New password', 'stillpress')} type="password" autoComplete="new-password" required minLength={12}
          hint={__('At least 12 characters.', 'stillpress')} value={password} onChange={(e) => setPassword(e.target.value)}
          error={errors.password} />
        <Field label={__('Repeat password', 'stillpress')} type="password" autoComplete="new-password" required value={confirm}
          onChange={(e) => setConfirm(e.target.value)} error={errors.confirm} />
        {captcha.widgets}
        {errors.captcha && <p role="alert" className="error">{errors.captcha}</p>}
        <button type="submit" className="primary" disabled={!captcha.ready}>{__('Set password', 'stillpress')}</button>
      </form>
    </main>
  );
}
