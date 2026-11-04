import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { __, sprintf } from '@wordpress/i18n';
import { formatInstant } from '../i18n/index.ts';
import { asApiError, type PublishState, type Role, type SessionUser, type Site, api } from '../api.ts';
import Shell from '../Shell.tsx';

export default function Dashboard() {
  const navigate = useNavigate();
  const [site, setSite] = useState<Site | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<Site>('/site').then((s) => !cancelled && setSite(s), (e: unknown) => {
      if (cancelled) return;
      if (asApiError(e).status === 401) void navigate('/login?next=' + encodeURIComponent('/'));
      else setError(__('Could not load the site.', 'stillpress'));
    });
    return () => { cancelled = true; };
  }, []);

  return (
    <Shell>
      {error && <p role="alert" className="error">{error}</p>}
      {site && (
        <>
          <h1>{site.title || __('Untitled site', 'stillpress')}</h1>
          <p><a href={site.homeUrl || '/'}>{__('View site', 'stillpress')}</a></p>
          <dl className="stats">
            <div><dt>{__('Posts and pages', 'stillpress')}</dt><dd>{site.items}</dd></div>
            <div><dt>{__('Comments', 'stillpress')}</dt><dd>{site.comments}</dd></div>
          </dl>
          <PublishCard timeZone={site.timezone} />
        </>
      )}
    </Shell>
  );
}

/** The publisher's state, polled while work is queued or running; administrators can start a full republish. */
function PublishCard({ timeZone }: { timeZone: string }) {
  const [state, setState] = useState<PublishState | null>(null);
  const [role, setRole] = useState<Role | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = () => api<PublishState>('/publish').then(setState, () => setMessage(__('Could not load the publish state.', 'stillpress')));
  useEffect(() => {
    let cancelled = false;
    void load();
    api<SessionUser>('/session').then((s) => !cancelled && setRole(s.role), () => {});
    return () => { cancelled = true; };
  }, []);
  useEffect(() => {
    if (state?.state !== 'running' && state?.state !== 'queued') return undefined;
    const timer = setTimeout(load, 1000);
    return () => clearTimeout(timer);
  }, [state]);

  async function republish() {
    setMessage(null);
    try {
      setState(await api<PublishState>('/publish', { method: 'POST' }));
    } catch (e) {
      const err = asApiError(e);
      setMessage(err.error === 'csrf' ? err.message! : __('Could not start the republish.', 'stillpress'));
    }
  }

  if (!state) return message ? <p role="alert" className="error">{message}</p> : null;
  return (
    <section className="publish" aria-labelledby="publish-heading">
      <h2 id="publish-heading">{__('Publishing', 'stillpress')}</h2>
      {state.state === 'idle' && <p role="status">{__('The site is up to date.', 'stillpress')}</p>}
      {state.state === 'queued' && <p role="status">{__('Changes are waiting to be published.', 'stillpress')}</p>}
      {state.state === 'running' && (
        <>
          <label htmlFor="publish-progress">{sprintf(__('Publishing… %1$d of about %2$d files', 'stillpress'), state.done, state.total)}</label>
          <progress id="publish-progress" max={Math.max(state.total, state.done, 1)} value={state.done} />
        </>
      )}
      {state.state === 'failed' && (
        <p role="alert" className="error">{sprintf(__('Publishing failed: %s It is retried in a minute.', 'stillpress'), state.error ?? __('see the server log.', 'stillpress'))}</p>
      )}
      <p className="muted">
        {sprintf(__('Last published: %s', 'stillpress'), state.lastPublishedAt ? formatInstant(state.lastPublishedAt, timeZone) : __('never', 'stillpress'))}
      </p>
      {message && <p role="alert" className="error">{message}</p>}
      {role === 'administrator' && (
        // never disabled: a run that died would keep it stuck, and a second request just waits for the publish lock
        <button type="button" onClick={republish}>{__('Republish site', 'stillpress')}</button>
      )}
    </section>
  );
}
