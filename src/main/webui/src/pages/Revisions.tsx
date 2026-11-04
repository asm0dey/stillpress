import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { type Change, diffLines, diffWords } from 'diff';
import { type ApiError, api } from '../api.ts';
import Shell from '../Shell.tsx';
import { type Message, saveError } from '../editor/errors.ts';

interface Line { id: number; author: string | null; createdAt: string }
interface Rev { title: string; body: string }
interface Full extends Rev { id: number; author: string | null; createdAt: string }

/** Word diff of the titles, line diff of the serialized bodies. */
export function compare(a: Rev, b: Rev): { title: Change[]; body: Change[] } {
  return { title: diffWords(a.title, b.title), body: diffLines(a.body, b.body) };
}

/** One side of the comparison: the "from" side drops additions, the "to" side drops removals. */
function Side({ changes, side, block }: { changes: Change[]; side: 'from' | 'to'; block: boolean }) {
  const hide = side === 'from' ? 'added' : 'removed';
  const mark = side === 'from' ? 'removed' : 'added';
  return (
    <>
      {changes.filter((c) => !c[hide]).map((c, i) => {
        const cls = c[mark] ? `diff-${mark}` : undefined;
        const sign = c[mark] ? (side === 'from' ? '−' : '+') : undefined;
        if (!block) {
          return <span key={i} className={cls}>{sign && <span aria-label={mark} className="diff-sign">{sign}</span>}{c.value}</span>;
        }
        return c.value.replace(/\n$/, '').split('\n').map((l, j) => (
          <div key={`${i}.${j}`} className={cls}><span aria-label={mark === 'added' ? 'added' : 'removed'} className="diff-sign">{sign ?? ' '}</span>{l}</div>
        ));
      })}
    </>
  );
}

/** The revisions of a post (`kind` items) or a reusable block: pick two to compare, restore one. */
export default function Revisions({ kind }: { kind: 'items' | 'blocks' }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const [list, setList] = useState<Line[] | null>(null);
  const [from, setFrom] = useState<Full | null>(null);
  const [to, setTo] = useState<Full | null>(null);
  const [message, setMessage] = useState<Message | null>(null);
  const base = `/${kind}/${id}`;
  const back = kind === 'items' ? `/edit/${id}` : `/blocks/${id}`;
  const noun = kind === 'items' ? 'post' : 'block';

  const load = (rid: number) => api<Full>(`${base}/revisions/${rid}`);
  useEffect(() => {
    api<Line[]>(`${base}/revisions`).then(async (l) => {
      setList(l);
      // newest on top: compare the one before the newest with the newest
      if (l[0]) setTo(await load(l[0].id));
      if (l[1] ?? l[0]) setFrom(await load((l[1] ?? l[0]).id));
    }, () => setMessage({ kind: 'alert', text: 'Could not load the revisions.' }));
  }, [id, kind]);

  async function pick(side: 'from' | 'to', rid: number) {
    try {
      const r = await load(rid);
      (side === 'from' ? setFrom : setTo)(r);
    } catch { setMessage({ kind: 'alert', text: 'Could not load that revision.' }); }
  }

  async function restore(rid: number) {
    setMessage(null);
    try {
      const r = await api<{ status?: string }>(`${base}/revisions/${rid}/restore`, { method: 'POST' });
      navigate(back, { state: { notice: r.status === 'publish' || kind === 'blocks' ? 'Restored. The site updates in a few seconds.' : 'Restored.' } });
    } catch (e) { setMessage(saveError(e as ApiError, noun)); }
  }

  const diff = from && to ? compare(from, to) : null;
  const when = (l: Line) => `${new Date(l.createdAt).toLocaleString()} by ${l.author ?? 'unknown'}`;
  return (
    <Shell>
      <div className="heading-row">
        <h1>Revisions</h1>
        <Link to={back}>Back to the editor</Link>
      </div>
      {message && (
        <div role="alert" className="error">
          <p>{message.text}</p>
          {message.list && <ul>{message.list.map((v) => <li key={v}>{v}</li>)}</ul>}
        </div>
      )}
      {list && (list.length === 0 ? <p className="muted">No revisions yet.</p> : (
        <>
          <table className="table">
            <thead><tr><th>From</th><th>To</th><th>Saved</th><th /></tr></thead>
            <tbody>
              {list.map((l) => (
                <tr key={l.id}>
                  <td><input type="radio" name="from" aria-label={`Compare from ${when(l)}`} checked={from?.id === l.id} onChange={() => pick('from', l.id)} /></td>
                  <td><input type="radio" name="to" aria-label={`Compare to ${when(l)}`} checked={to?.id === l.id} onChange={() => pick('to', l.id)} /></td>
                  <td>{when(l)}</td>
                  <td><button type="button" onClick={() => restore(l.id)}>Restore this revision</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          {diff && (
            <div className="diff">
              {(['from', 'to'] as const).map((side) => (
                <section key={side} aria-label={side === 'from' ? 'From' : 'To'}>
                  <h2>{side === 'from' ? 'From' : 'To'}</h2>
                  <h3><Side changes={diff.title} side={side} block={false} /></h3>
                  <pre><Side changes={diff.body} side={side} block /></pre>
                </section>
              ))}
            </div>
          )}
        </>
      ))}
    </Shell>
  );
}
