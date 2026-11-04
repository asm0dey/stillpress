import { type FormEvent, useEffect, useState } from 'react';
import { __ } from '@wordpress/i18n';
import { type SessionUser, api, asApiError } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';

/** The signed-in user's own display name and email, for every role; the language is switched in the header. */
export default function Profile() {
  const [me, setMe] = useState<SessionUser | null>(null);
  const [form, setForm] = useState({ displayName: '', email: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api<SessionUser>('/session').then((s) => {
      if (cancelled) return;
      setMe(s);
      setForm({ displayName: s.displayName ?? '', email: s.email ?? '' });
    }, () => !cancelled && setMessage(__('Could not load the profile.', 'stillpress')));
    return () => { cancelled = true; };
  }, []);

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!me) return;
    setMessage(null);
    setSaved(false);
    setErrors({});
    try {
      await api(`/users/${me.id}`, { method: 'PUT', body: form });
      setSaved(true);
    } catch (e) {
      const err = asApiError(e);
      if (err.error === 'email_taken') setErrors({ email: __('Another user has this email.', 'stillpress') });
      else if (err.field === 'email') setErrors({ email: __('Use an email address of at most 100 characters.', 'stillpress') });
      else if (err.field === 'displayName') setErrors({ displayName: __('Enter a name of at most 250 characters.', 'stillpress') });
      else setMessage(err.error === 'csrf' ? err.message! : __('Could not save the profile.', 'stillpress'));
    }
  }

  return (
    <Shell>
      <h1>{__('Profile', 'stillpress')}</h1>
      {message && <p role="alert" className="error">{message}</p>}
      {saved && <p role="status">{__('Profile saved.', 'stillpress')}</p>}
      {me && (
        <form onSubmit={save} noValidate>
          <p className="muted">{me.login}</p>
          <Field label={__('Display name', 'stillpress')} name="displayName" required value={form.displayName} error={errors.displayName}
            hint={__('The author archive address stays the same.', 'stillpress')} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
          <Field label={__('Email', 'stillpress')} name="email" type="email" required value={form.email} error={errors.email}
            onChange={(e) => setForm({ ...form, email: e.target.value })} />
          <button type="submit" className="primary">{__('Save', 'stillpress')}</button>
        </form>
      )}
    </Shell>
  );
}
