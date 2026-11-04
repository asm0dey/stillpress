import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { type ApiError, type PublishState, type Role, type SessionUser, type Site, api } from '../api.ts';
import Shell from '../Shell.tsx';

export default function Dashboard() {
  const navigate = useNavigate();
  const [site, setSite] = useState<Site | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Site>('/site').then(setSite, (e: ApiError) => (e.status === 401 ? navigate('/login') : setError('Could not load the site.')));
  }, []);

  return (
    <Shell>
      {error && <p role="alert" className="error">{error}</p>}
      {site && (
        <>
          <h1>{site.title || 'Untitled site'}</h1>
          <p><a href={site.homeUrl || '/'}>View site</a></p>
          <dl className="stats">
            <div><dt>Posts and pages</dt><dd>{site.items}</dd></div>
            <div><dt>Comments</dt><dd>{site.comments}</dd></div>
          </dl>
          <PublishCard />
        </>
      )}
    </Shell>
  );
}

/** The publisher's state, polled while work is queued or running; administrators can start a full republish. */
function PublishCard() {
  const [state, setState] = useState<PublishState | null>(null);
  const [role, setRole] = useState<Role | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = () => api<PublishState>('/publish').then(setState, () => setMessage('Could not load the publish state.'));
  useEffect(() => {
    load();
    api<SessionUser>('/session').then((s) => setRole(s.role), () => {});
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
      const err = e as ApiError;
      setMessage(err.error === 'csrf' ? err.message! : 'Could not start the republish.');
    }
  }

  if (!state) return message ? <p role="alert" className="error">{message}</p> : null;
  const busy = state.state === 'running' || state.state === 'queued';
  return (
    <section className="publish" aria-labelledby="publish-heading">
      <h2 id="publish-heading">Publishing</h2>
      {state.state === 'idle' && <p role="status">The site is up to date.</p>}
      {state.state === 'queued' && <p role="status">Changes are waiting to be published.</p>}
      {state.state === 'running' && (
        <>
          <label htmlFor="publish-progress">Publishing… {state.done} of about {state.total} files</label>
          <progress id="publish-progress" max={Math.max(state.total, state.done, 1)} value={state.done} />
        </>
      )}
      {state.state === 'failed' && (
        <p role="alert" className="error">Publishing failed: {state.error ?? 'see the server log.'} It is retried in a minute.</p>
      )}
      <p className="muted">
        Last published: {state.lastPublishedAt ? new Date(state.lastPublishedAt).toLocaleString() : 'never'}
      </p>
      {message && <p role="alert" className="error">{message}</p>}
      {role === 'administrator' && (
        <button type="button" onClick={republish} disabled={busy}>Republish site</button>
      )}
    </section>
  );
}
