import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { type ApiError, type ReusableBlock, type SessionUser, api } from '../api.ts';
import Shell from '../Shell.tsx';

/** The reusable blocks: title, how many items use each, last change; authors and up add new ones. */
export default function Blocks() {
  const navigate = useNavigate();
  const [list, setList] = useState<ReusableBlock[] | null>(null);
  const [session, setSession] = useState<SessionUser | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    api<ReusableBlock[]>('/blocks').then(setList, () => setMessage('Could not load the list.'));
    api<SessionUser>('/session').then(setSession, () => {});
  }, []);

  async function add() {
    setMessage(null);
    try {
      const b = await api<ReusableBlock>('/blocks', { method: 'POST', body: { title: '', content: '' } });
      navigate(`/blocks/${b.id}`);
    } catch (e) {
      const err = e as ApiError;
      setMessage(err.error === 'csrf' ? err.message! : err.status === 403 ? 'You cannot add one.' : 'Could not add one.');
    }
  }

  return (
    <Shell>
      <div className="heading-row">
        <h1>Reusable blocks</h1>
        {session && session.role !== 'contributor' && <button type="button" className="primary" onClick={add}>Add new block</button>}
      </div>
      {message && <p role="alert" className="error">{message}</p>}
      {list && (
        list.length === 0 ? <p className="muted">No reusable blocks yet.</p> : (
          <table className="table">
            <thead><tr><th>Title</th><th>Author</th><th>Used by</th><th>Last modified</th></tr></thead>
            <tbody>
              {list.map((b) => (
                <tr key={b.id}>
                  <td>
                    <Link to={`/blocks/${b.id}`}>{b.title || '(no title)'}</Link>
                    {!b.canEdit && <span className="muted"> (view only)</span>}
                  </td>
                  <td>{b.author ?? '—'}</td>
                  {/* shortcut: plain text; the posts list has no "uses block" filter to link to */}
                  <td>{b.usedBy} {b.usedBy === 1 ? 'item' : 'items'}</td>
                  <td>{b.modifiedAt?.slice(0, 16).replace('T', ' ')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      )}
    </Shell>
  );
}
