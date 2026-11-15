import { type FormEvent, useEffect, useState } from 'react';
import { Link } from 'react-router';
import { __, sprintf } from '@wordpress/i18n';
import { asApiError, type Factors, type SessionUser, type TotpEnrollment, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';
import { passkeyAttestation, passkeysSupported } from '../webauthn.ts';

const date = (iso: string) => new Date(iso).toLocaleDateString();
/** The browser's platform, as the passkey's default name. */
const platformName = (): string =>
  (navigator as Navigator & { userAgentData?: { platform?: string } }).userAgentData?.platform || navigator.platform || __('Passkey', 'stillpress');

/** The signed-in user's own second sign-in methods: one authenticator app and any number of passkeys. */
export default function TwoFactor() {
  const [factors, setFactors] = useState<Factors | null>(null);
  const [me, setMe] = useState<SessionUser | null>(null);
  const [enrollment, setEnrollment] = useState<TotpEnrollment | null>(null);
  const [code, setCode] = useState('');
  const [name, setName] = useState(platformName);
  const [added, setAdded] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [codeError, setCodeError] = useState<string>();

  const load = () => Promise.all([api<Factors>('/2fa'), api<SessionUser>('/session')])
    .then(([f, s]) => { setFactors(f); setMe(s); }, () => setMessage(__('Could not load your two-factor settings.', 'stillpress')));
  useEffect(() => { void load(); }, []);

  /** Runs a change, then reloads; a failure shows its message, and nothing for a cancelled browser prompt. */
  async function act(run: () => Promise<unknown>, failed: string, messages: Record<string, string> = {}) {
    setMessage(null);
    try {
      await run();
    } catch (e) {
      if (e instanceof DOMException && e.name === 'NotAllowedError') return false;
      const err = asApiError(e);
      setMessage(err.error === 'csrf' ? err.message! : messages[err.error ?? ''] ?? failed);
      return false;
    } finally {
      await load();
    }
    return true;
  }

  const lastFactor = { last_factor: __("This site requires two-factor authentication, so you can't remove your last method.", 'stillpress') };

  const startTotp = () => act(async () => setEnrollment(await api<TotpEnrollment>('/2fa/totp', { method: 'POST' })),
    __('Could not start the setup.', 'stillpress'), { totp_exists: __('An authenticator app is already set up.', 'stillpress') });

  async function confirmTotp(e: FormEvent) {
    e.preventDefault();
    setCodeError(undefined);
    setMessage(null);
    try {
      await api('/2fa/totp/confirm', { method: 'POST', body: { code } });
    } catch (err) {
      const f = asApiError(err);
      if (f.error === 'invalid_code') setCodeError(__('That code is not right. Try the next one.', 'stillpress'));
      else setMessage(f.error === 'csrf' ? f.message! : __('Could not confirm the code.', 'stillpress'));
      return;
    }
    setEnrollment(null);
    setCode('');
    setAdded(true);
    await load();
  }

  async function removeTotp() {
    // an unconfirmed setup is cancelled without asking
    if (!enrollment && !confirm(__('Remove the authenticator app?', 'stillpress'))) return;
    await act(() => api('/2fa/totp', { method: 'DELETE' }), __('Could not remove the authenticator app.', 'stillpress'), lastFactor);
    setEnrollment(null);
  }

  async function addPasskey(e: FormEvent) {
    e.preventDefault();
    if (!me) return;
    const ok = await act(async () => {
      const credential = await passkeyAttestation(me.login, me.displayName);
      await api('/2fa/passkeys', { method: 'POST', body: { name: name.trim(), credential } });
    }, __('Could not add the passkey.', 'stillpress'), {
      invalid_passkey: __('The browser did not give a valid passkey. Try again.', 'stillpress'),
      invalid: __('Enter a name of 1 to 100 characters.', 'stillpress'),
    });
    if (ok) setAdded(true);
  }

  const removePasskey = (id: number, label: string) => confirm(sprintf(__('Remove the passkey "%s"?', 'stillpress'), label))
    && act(() => api(`/2fa/passkeys/${id}`, { method: 'DELETE' }), __('Could not remove the passkey.', 'stillpress'), lastFactor);

  const count = factors ? (factors.totp ? 1 : 0) + factors.passkeys.length : 0;
  return (
    <Shell>
      <h1>{__('Two-factor authentication', 'stillpress')}</h1>
      {me?.enrollRequired && (
        <p role="status" className="banner">
          {__('This site requires two-factor authentication. Add a passkey or an authenticator app to continue.', 'stillpress')}
        </p>
      )}
      {added && me && !me.enrollRequired && <p role="status"><Link to="/">{__('Continue', 'stillpress')}</Link></p>}
      {message && <p role="alert" className="error">{message}</p>}
      {factors && (
        <>
          {count === 1 && <p className="hint">{__('Add a second method in case you lose this one.', 'stillpress')}</p>}
          <section aria-labelledby="totp-heading">
            <h2 id="totp-heading">{__('Authenticator app', 'stillpress')}</h2>
            {factors.totp ? (
              <button type="button" onClick={removeTotp}>{__('Remove', 'stillpress')}</button>
            ) : enrollment ? (
              <form onSubmit={confirmTotp}>
                <img src={'data:image/svg+xml;utf8,' + encodeURIComponent(enrollment.qrSvg)} alt={__('QR code for your authenticator app', 'stillpress')} />
                <p>{__('Or enter this key by hand:', 'stillpress')} <code>{enrollment.secret}</code></p>
                <Field label={__('6-digit code', 'stillpress')} name="code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}"
                  maxLength={6} required value={code} error={codeError} onChange={(e) => setCode(e.target.value)} />
                <button type="submit" className="primary">{__('Confirm', 'stillpress')}</button>{' '}
                <button type="button" onClick={removeTotp}>{__('Cancel', 'stillpress')}</button>
              </form>
            ) : (
              <button type="button" onClick={startTotp}>{__('Set up', 'stillpress')}</button>
            )}
          </section>
          <section aria-labelledby="passkeys-heading">
            <h2 id="passkeys-heading">{__('Passkeys', 'stillpress')}</h2>
            {factors.passkeys.length > 0 && (
              <table>
                <thead><tr><th>{__('Name', 'stillpress')}</th><th>{__('Added', 'stillpress')}</th><th>{__('Last used', 'stillpress')}</th><th /></tr></thead>
                <tbody>
                  {factors.passkeys.map((p) => (
                    <tr key={p.id}>
                      <td>{p.name}</td>
                      <td>{date(p.createdAt)}</td>
                      <td>{p.lastUsedAt ? date(p.lastUsedAt) : __('Never', 'stillpress')}</td>
                      <td><button type="button" aria-label={sprintf(__('Remove %s', 'stillpress'), p.name)} onClick={() => removePasskey(p.id, p.name)}>{__('Remove', 'stillpress')}</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {passkeysSupported() ? (
              <form onSubmit={addPasskey}>
                <Field label={__('Passkey name', 'stillpress')} name="passkey-name" required maxLength={100} value={name} onChange={(e) => setName(e.target.value)}
                  hint={sprintf(__('A passkey works only on the address it was added on (%s). After the site moves to its final domain, add your passkeys again.', 'stillpress'), location.host)} />
                <button type="submit">{__('Add a passkey', 'stillpress')}</button>
              </form>
            ) : <p className="muted">{__('This browser does not support passkeys.', 'stillpress')}</p>}
          </section>
        </>
      )}
    </Shell>
  );
}
