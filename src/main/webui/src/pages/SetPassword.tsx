import { type FormEvent, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { type ApiError, CSRF_MESSAGE, api } from '../api.ts';
import { Field } from '../Field.tsx';

export default function SetPassword() {
  const [params] = useSearchParams();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<{ password?: string; confirm?: string }>({});
  const [state, setState] = useState<'form' | 'done' | 'expired' | 'failed' | 'csrf'>('form');

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (password !== confirm) {
      setErrors({ confirm: 'The passwords do not match.' });
      return;
    }
    setErrors({});
    try {
      await api('/set-password', { method: 'POST', body: { token: params.get('token'), password } });
      setState('done');
    } catch (e) {
      const err = e as ApiError;
      if (err.error === 'csrf') setState('csrf');
      else if (err.status === 400) setErrors({ password: 'Use 12 to 1024 characters.' });
      else setState(err.status === 410 ? 'expired' : 'failed');
    }
  }

  if (state === 'done') {
    return <main className="page narrow"><h1>Password set</h1><p><Link to="/login">Log in</Link> with your new password.</p></main>;
  }
  return (
    <main className="page narrow">
      <h1>Set your password</h1>
      {state === 'expired' && <p role="alert" className="error">This link is invalid or has expired. Ask an administrator for a new one.</p>}
      {state === 'csrf' && <p role="alert" className="error">{CSRF_MESSAGE}</p>}
      {state === 'failed' && <p role="alert" className="error">Could not set the password. Try again.</p>}
      <form onSubmit={submit} noValidate>
        <Field label="New password" type="password" autoComplete="new-password" required minLength={12}
          hint="At least 12 characters." value={password} onChange={(e) => setPassword(e.target.value)}
          error={errors.password} />
        <Field label="Repeat password" type="password" autoComplete="new-password" required value={confirm}
          onChange={(e) => setConfirm(e.target.value)} error={errors.confirm} />
        <button type="submit" className="primary">Set password</button>
      </form>
    </main>
  );
}
