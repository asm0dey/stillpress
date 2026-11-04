import { Link, Navigate } from 'react-router';
import { type ChangeEvent, type FormEvent, useEffect, useState } from 'react';
import { __, sprintf } from '@wordpress/i18n';
import { type ApiError, type ImportStatus, api } from '../api.ts';
import { Field } from '../Field.tsx';
import Shell from '../Shell.tsx';

const DEFAULTS = { postsPerPage: '10', postsPerRss: '10', categoryBase: 'category', tagBase: 'tag' };

/** Uploads a WordPress export and follows the background import until it is done or has failed. */
export default function Import() {
  const [status, setStatus] = useState<ImportStatus | null>(null);
  const [form, setForm] = useState(DEFAULTS);
  const [file, setFile] = useState<File | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => api<ImportStatus>('/import').then(setStatus, () => setMessage(__('Could not load the import status.', 'stillpress')));
  useEffect(() => { load(); }, []);
  useEffect(() => {
    if (status?.state !== 'running' || status.error) return undefined;
    const timer = setTimeout(load, 1000);
    return () => clearTimeout(timer);
  }, [status]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setMessage(null);
    if (!file) {
      setMessage(__('Choose the export file.', 'stillpress'));
      return;
    }
    const body = new FormData();
    body.append('file', file);
    Object.entries(form).forEach(([k, v]) => body.append(k, v));
    setBusy(true);
    try {
      setStatus(await api<ImportStatus>('/import', { method: 'POST', body }));
    } catch (e) {
      const err = e as ApiError;
      setMessage(err.error === 'csrf' ? err.message! : err.status === 409 ? err.message ?? __('This site has already been imported.', 'stillpress')
        : err.status === 400 ? err.error ?? __('Check the settings.', 'stillpress') : __('The upload failed. Try again.', 'stillpress'));
    } finally {
      setBusy(false);
    }
  }

  const set = (k: keyof typeof DEFAULTS) => (e: ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });
  const state = status?.state;
  const leftContent = state === 'failed' && status!.contentLeft;
  // persisted as running, but no import thread in this server: it died, or the CLI runs one elsewhere
  const stalled = state === 'running' && status!.error;

  // started empty, or edited before any import: the site's origin is decided
  if (state === 'closed') return <Navigate to="/" replace />;

  return (
    <Shell>
      <h1>{__('Import from WordPress', 'stillpress')}</h1>
      {message && <p role="alert" className="error">{message}</p>}
      {stalled && <p role="alert" className="error">{status!.error}</p>}
      {state === 'running' && !stalled && (
        <p role="status">{sprintf(__('Importing… %1$d posts, pages and attachments, %2$d media files so far.', 'stillpress'), status!.items, status!.media)}</p>
      )}
      {state === 'done' && (
        <>
          <p role="status">{__('The import is done. The site is publishing in the background.', 'stillpress')} <Link to="/">{__('Follow its progress on the dashboard', 'stillpress')}</Link></p>
          {status!.report && <pre className="report">{status!.report}</pre>}
        </>
      )}
      {state === 'failed' && (
        <div role="alert">
          <p className="error">{sprintf(__('The import failed: %s', 'stillpress'), status!.error ?? __('see the server log.', 'stillpress'))}</p>
          {leftContent
            ? <p>{__('Content was already written. To start over, use Reset site at the bottom of Settings.', 'stillpress')} <Link to="/settings">{__('Open Settings', 'stillpress')}</Link></p>
            : <p>{__('Nothing was imported. Fix the file and try again.', 'stillpress')}</p>}
          {status!.report && <pre className="report">{status!.report}</pre>}
        </div>
      )}
      {state === 'idle' && status!.contentLeft && (
        <p role="status">
          {__('This site already has content, and import works only into an empty site. To start over, use Reset site at the bottom of Settings.', 'stillpress')}{' '}
          <Link to="/settings">{__('Open Settings', 'stillpress')}</Link>
        </p>
      )}
      {(state === 'idle' || state === 'failed') && !status!.contentLeft && (
        <form onSubmit={submit}>
          <p className="muted">
            {__('Export your site in WordPress under Tools → Export → All content. The import replaces this site’s title and language with the export’s, and can run only once.', 'stillpress')}
          </p>
          <div className="field">
            <label htmlFor="wxr">{__('Export file (.xml)', 'stillpress')}</label>
            <input id="wxr" type="file" accept=".xml,text/xml,application/xml" required
              onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </div>
          <fieldset>
            <legend>{__('Settings the export does not carry', 'stillpress')}</legend>
            <p className="hint">
              {__('The defaults are WordPress’s. Settings → Reading and Settings → Permalinks in your WordPress show the real values.', 'stillpress')}
            </p>
            <Field label={__('Posts per page', 'stillpress')} type="number" min="1" required value={form.postsPerPage}
              onChange={set('postsPerPage')} />
            <Field label={__('Items per feed', 'stillpress')} type="number" min="1" required value={form.postsPerRss}
              onChange={set('postsPerRss')} />
            <Field label={__('Category base', 'stillpress')} required value={form.categoryBase} onChange={set('categoryBase')} />
            <Field label={__('Tag base', 'stillpress')} required value={form.tagBase} onChange={set('tagBase')} />
          </fieldset>
          <button type="submit" className="primary" disabled={busy}>{busy ? __('Uploading…', 'stillpress') : __('Import', 'stillpress')}</button>
        </form>
      )}
    </Shell>
  );
}
