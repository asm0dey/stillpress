import { type ChangeEvent, type FormEvent, useEffect, useState } from 'react';
import { __, sprintf } from '@wordpress/i18n';
import { asApiError, type MailSettings, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';

const mailMessages = (): Record<string, string> => ({
  host: __('Enter the SMTP server, or leave it empty to turn mail off.', 'stillpress'),
  port: __('Use a port from 1 to 65535.', 'stillpress'),
  security: __('Choose how to secure the connection.', 'stillpress'),
  from: __('Use an email address such as blog@example.org.', 'stillpress'),
});

const testMessages = (): Record<string, string> => ({
  no_email: __('Your profile has no email to send the test to.', 'stillpress'),
  mail_not_configured: __('Save an SMTP server first.', 'stillpress'),
});

/** The administrator's security settings: for now, the SMTP server the site mails through. */
export default function Security() {
  return (
    <Shell>
      <h1>{__('Security', 'stillpress')}</h1>
      <EmailSettings />
    </Shell>
  );
}

function EmailSettings() {
  const [form, setForm] = useState<MailSettings | null>(null);
  // null keeps the stored password; the server never sends it back
  const [password, setPassword] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api<MailSettings>('/mail').then((m) => !cancelled && setForm(m),
      () => !cancelled && setMessage(__('Could not load the email settings.', 'stillpress')));
    return () => { cancelled = true; };
  }, []);

  if (!form) return message && <p role="alert" className="error">{message}</p>;

  const text = (k: 'host' | 'security' | 'username' | 'from') => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm({ ...form, [k]: e.target.value });

  async function save(e: FormEvent) {
    e.preventDefault();
    setMessage(null);
    setErrors({});
    setStatus(null);
    try {
      const { host, port, security, username, from } = form!;
      setForm(await api<MailSettings>('/mail', { method: 'PUT', body: { host, port, security, username, from, password } }));
      setPassword(null);
      setStatus(__('Saved.', 'stillpress'));
    } catch (err) {
      const f = asApiError(err);
      if (f.field) setErrors({ [f.field]: mailMessages()[f.field] ?? __('Check this field.', 'stillpress') });
      else setMessage(f.error === 'csrf' ? f.message! : __('Could not save the email settings.', 'stillpress'));
    }
  }

  async function sendTest() {
    setMessage(null);
    setStatus(null);
    setBusy(true);
    try {
      await api('/mail/test', { method: 'POST' });
      setForm({ ...form!, lastError: null });
      setStatus(__('The test email was sent. Check your inbox.', 'stillpress'));
    } catch (err) {
      const f = asApiError(err);
      setMessage(f.error === 'mail_failed'
        ? sprintf(__('The test email could not be sent: %s', 'stillpress'), f.message ?? '')
        : f.error === 'csrf' ? f.message! : testMessages()[f.error ?? ''] ?? __('Could not send the test email.', 'stillpress'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="email-heading">
      <h2 id="email-heading">{__('Email', 'stillpress')}</h2>
      {form.lastError && (
        <p role="alert" className="error">{sprintf(__('The last email could not be sent: %s', 'stillpress'), form.lastError)}</p>
      )}
      <form onSubmit={save}>
        <Field label={__('SMTP host', 'stillpress')} name="host" value={form.host} onChange={text('host')} error={errors.host}
          hint={__('Leave empty to send no email.', 'stillpress')} />
        <Field label={__('Port', 'stillpress')} name="port" type="number" min={1} max={65535} value={form.port}
          onChange={(e) => setForm({ ...form, port: Number(e.target.value) })} error={errors.port} />
        <div className="field">
          <label htmlFor="security">{__('Connection security', 'stillpress')}</label>
          <select id="security" value={form.security} onChange={text('security')}>
            <option value="starttls">{__('STARTTLS', 'stillpress')}</option>
            <option value="tls">{__('TLS', 'stillpress')}</option>
            <option value="none">{__('None', 'stillpress')}</option>
          </select>
        </div>
        <Field label={__('Username', 'stillpress')} name="username" autoComplete="off" value={form.username} onChange={text('username')} />
        <Field label={__('Password', 'stillpress')} name="smtp-password" type="password" autoComplete="new-password"
          placeholder={form.passwordSet ? __('Saved', 'stillpress') : undefined} value={password ?? ''}
          onChange={(e) => setPassword(e.target.value)} />
        <Field label={__('From address', 'stillpress')} name="from" type="email" value={form.from} onChange={text('from')} error={errors.from} />
        {message && <p role="alert" className="error">{message}</p>}
        {status && <p role="status">{status}</p>}
        <button type="submit" className="primary">{__('Save', 'stillpress')}</button>
        {' '}
        <button type="button" onClick={sendTest} disabled={busy || !form.host}>{__('Send test email', 'stillpress')}</button>
      </form>
    </section>
  );
}
