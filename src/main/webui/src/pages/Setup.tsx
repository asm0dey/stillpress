import { type ChangeEvent, type FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { type ApiError, api } from '../api.ts';
import { Field } from '../Field.tsx';

interface Form {
  token: string; title: string; language: string; login: string; email: string; displayName: string;
  password: string;
}

const MESSAGES: Record<string, string> = {
  token: 'Enter the setup token printed in the server log.',
  title: 'Give the site a title.',
  language: 'Use a language code such as en or en-GB.',
  login: 'Choose a login name.',
  email: 'Enter a valid email address.',
  displayName: 'Enter the name shown on your posts.',
  password: 'Use 12 to 1024 characters.',
};

export default function Setup() {
  const navigate = useNavigate();
  const [form, setForm] = useState<Form>({
    token: '', title: '', language: 'en', login: '', email: '', displayName: '', password: '',
  });
  const [errors, setErrors] = useState<Partial<Record<keyof Form, string>>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  const field = (name: keyof Form) => ({
    name,
    value: form[name],
    onChange: (e: ChangeEvent<HTMLInputElement>) => setForm({ ...form, [name]: e.target.value }),
    error: errors[name],
    required: true,
  });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErrors({});
    setProblem(null);
    try {
      await api('/setup', { method: 'POST', body: form });
      setDone(true);
    } catch (e) {
      const err = e as ApiError;
      if (err.error === 'csrf') setProblem(err.message!);
      else if (err.status === 403) setErrors({ token: 'That token does not match the one in the server log.' });
      else if (err.status === 400) setErrors({ [err.field!]: MESSAGES[err.field!] ?? 'Check this value.' });
      else if (err.status === 409) setProblem('already');
      else setProblem('Setup failed. Check the server log and try again.');
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <main className="page">
        <h1>Your site is ready</h1>
        <p className="muted">How do you want to begin?</p>
        <div className="cards">
          <button type="button" className="card" onClick={() => navigate('/')}>
            <strong>Start empty</strong>
            <span>Begin with a blank site and write your first post.</span>
          </button>
          <button type="button" className="card" onClick={() => navigate('/import')}>
            <strong>Import from WordPress</strong>
            <span>Bring posts, pages, comments and media from a WordPress export file.</span>
          </button>
        </div>
      </main>
    );
  }

  return (
    <main className="page narrow">
      <h1>Set up Stillpress</h1>
      {problem === 'already' ? (
        <p role="alert" className="error">This site is already set up. <Link to="/login">Log in</Link> instead.</p>
      ) : problem && <p role="alert" className="error">{problem}</p>}
      <form onSubmit={submit} noValidate>
        <fieldset>
          <legend>Site</legend>
          <Field label="Setup token" autoComplete="off" spellCheck="false" {...field('token')}
            hint="Printed in the server log as “Setup token: …”." />
          <Field label="Site title" {...field('title')} />
          <Field label="Language" hint="A code such as en or en-GB." {...field('language')} />
        </fieldset>
        <fieldset>
          <legend>Administrator</legend>
          <Field label="Login" autoComplete="username" {...field('login')} />
          <Field label="Email" type="email" autoComplete="email" {...field('email')} />
          <Field label="Display name" autoComplete="name" {...field('displayName')} />
          <Field label="Password" type="password" autoComplete="new-password" minLength={12}
            hint="At least 12 characters." {...field('password')} />
        </fieldset>
        <button type="submit" className="primary" disabled={busy}>{busy ? 'Creating…' : 'Create site'}</button>
      </form>
    </main>
  );
}
