import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { __, _n, sprintf } from '@wordpress/i18n';
import { formatInstant } from '../i18n/index.ts';
import { asApiError, type ApiError, type ReusableBlock, type SessionUser, type Site, api } from '../api.ts';
import Shell from '../Shell.tsx';

/** The reusable blocks: title, how many items use each, last change; authors and up add new ones. */
export default function Blocks() {
  const navigate = useNavigate();
  const [list, setList] = useState<ReusableBlock[] | null>(null);
  const [session, setSession] = useState<SessionUser | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [timeZone, setTimeZone] = useState('UTC');

  useEffect(() => {
    let cancelled = false;
    api<ReusableBlock[]>('/blocks').then((l) => !cancelled && setList(l), () => !cancelled && setMessage(__('Could not load the list.', 'stillpress')));
    api<SessionUser>('/session').then((s) => !cancelled && setSession(s), () => {});
    api<Site>('/site').then((s) => !cancelled && setTimeZone(s.timezone), () => {});
    return () => { cancelled = true; };
  }, []);

  async function add() {
    setMessage(null);
    try {
      const b = await api<ReusableBlock>('/blocks', { method: 'POST', body: { title: '', content: '' } });
      navigate(`/blocks/${b.id}`);
    } catch (e) {
      const err = asApiError(e);
      setMessage(err.error === 'csrf' ? err.message! : err.status === 403 ? __('You cannot add one.', 'stillpress') : __('Could not add one.', 'stillpress'));
    }
  }

  return (
    <Shell>
      <div className="heading-row">
        <h1>{__('Reusable blocks', 'stillpress')}</h1>
        {session && session.role !== 'contributor' && <button type="button" className="primary" onClick={add}>{__('Add new block', 'stillpress')}</button>}
      </div>
      {message && <p role="alert" className="error">{message}</p>}
      {list && (
        list.length === 0 ? <p className="muted">{__('No reusable blocks yet.', 'stillpress')}</p> : (
          <table className="table">
            <thead><tr><th>{__('Title', 'stillpress')}</th><th>{__('Author', 'stillpress')}</th><th>{__('Used by', 'stillpress')}</th><th>{__('Last modified', 'stillpress')}</th></tr></thead>
            <tbody>
              {list.map((b) => (
                <tr key={b.id}>
                  <td>
                    <Link to={`/blocks/${b.id}`}>{b.title || __('(no title)', 'stillpress')}</Link>
                    {!b.canEdit && <span className="muted"> {__('(view only)', 'stillpress')}</span>}
                  </td>
                  <td>{b.author ?? '—'}</td>
                  {/* shortcut: plain text; the posts list has no "uses block" filter to link to */}
                  <td>{sprintf(_n('%d item', '%d items', b.usedBy, 'stillpress'), b.usedBy)}</td>
                  <td>{b.modifiedAt && formatInstant(b.modifiedAt, timeZone)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      )}
    </Shell>
  );
}
