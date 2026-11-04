import { type ChangeEvent, type FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { __ } from '@wordpress/i18n';
import { asApiError, type ApiError, api } from '../api.ts';
import { Field } from '../Field.tsx';

interface Form {
  token: string; title: string; language: string; login: string; email: string; displayName: string;
  password: string;
}

const messages = (): Record<string, string> => ({
  token: __('Enter the setup token printed in the server log.', 'stillpress'),
  title: __('Give the site a title.', 'stillpress'),
  language: __('Use a language code such as en or en-GB.', 'stillpress'),
  login: __('Choose a login name.', 'stillpress'),
  email: __('Enter a valid email address.', 'stillpress'),
  displayName: __('Enter the name shown on your posts.', 'stillpress'),
  password: __('Use 12 to 1024 characters.', 'stillpress'),
});

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

  async function startEmpty() {
    try {
      await api('/setup/start-empty', { method: 'POST' });
      navigate('/');
    } catch (e) {
      const err = asApiError(e);
      setProblem(err.error === 'csrf' ? err.message! : __('Could not start an empty site. Try again.', 'stillpress'));
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setErrors({});
    setProblem(null);
    try {
      await api('/setup', { method: 'POST', body: { ...form, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone } });
      setDone(true);
    } catch (e) {
      const err = asApiError(e);
      if (err.error === 'csrf') setProblem(err.message!);
      else if (err.status === 403) setErrors({ token: __('That token does not match the one in the server log.', 'stillpress') });
      else if (err.status === 400) setErrors({ [err.field!]: messages()[err.field!] ?? __('Check this value.', 'stillpress') });
      else if (err.status === 409) setProblem('already');
      else setProblem(__('Setup failed. Check the server log and try again.', 'stillpress'));
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <main className="page">
        <h1>{__('Your site is ready', 'stillpress')}</h1>
        <p className="muted">{__('How do you want to begin?', 'stillpress')}</p>
        {problem && <p role="alert" className="error">{problem}</p>}
        <div className="cards">
          <button type="button" className="card" onClick={startEmpty}>
            <strong>{__('Start empty', 'stillpress')}</strong>
            <span>{__('Begin with a blank site and write your first post.', 'stillpress')}</span>
          </button>
          <button type="button" className="card" onClick={() => navigate('/import')}>
            <strong>{__('Import from WordPress', 'stillpress')}</strong>
            <span>{__('Bring posts, pages, comments and media from a WordPress export file.', 'stillpress')}</span>
          </button>
        </div>
      </main>
    );
  }

  return (
    <main className="page narrow">
      <h1>{__('Set up Stillpress', 'stillpress')}</h1>
      {problem === 'already' ? (
        <p role="alert" className="error">{__('This site is already set up.', 'stillpress')} <Link to="/login">{__('Log in instead', 'stillpress')}</Link></p>
      ) : problem && <p role="alert" className="error">{problem}</p>}
      <form onSubmit={submit} noValidate>
        <fieldset>
          <legend>{__('Site', 'stillpress')}</legend>
          <Field label={__('Setup token', 'stillpress')} autoComplete="off" spellCheck="false" {...field('token')}
            hint={__('Printed in the server log as “Setup token: …”.', 'stillpress')} />
          <Field label={__('Site title', 'stillpress')} {...field('title')} />
          <Field label={__('Language', 'stillpress')} hint={__('A code such as en or en-GB.', 'stillpress')} {...field('language')} />
        </fieldset>
        <fieldset>
          <legend>{__('Administrator', 'stillpress')}</legend>
          <Field label={__('Login', 'stillpress')} autoComplete="username" {...field('login')} />
          <Field label={__('Email', 'stillpress')} type="email" autoComplete="email" {...field('email')} />
          <Field label={__('Display name', 'stillpress')} autoComplete="name" {...field('displayName')} />
          <Field label={__('Password', 'stillpress')} type="password" autoComplete="new-password" minLength={12}
            hint={__('At least 12 characters.', 'stillpress')} {...field('password')} />
        </fieldset>
        <button type="submit" className="primary" disabled={busy}>{busy ? __('Creating…', 'stillpress') : __('Create site', 'stillpress')}</button>
      </form>
    </main>
  );
}
