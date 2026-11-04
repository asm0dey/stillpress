import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { type Change, diffLines, diffWords } from 'diff';
import { __, _n, _x, sprintf } from '@wordpress/i18n';
import { formatInstant } from '../i18n/index.ts';
import { asApiError, type ApiError, type Site, api } from '../api.ts';
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
  const word = mark === 'added' ? __('Added: ', 'stillpress') : __('Removed: ', 'stillpress');
  return (
    <>
      {changes.filter((c) => !c[hide]).map((c, i) => {
        const changed = !!c[mark];
        const cls = changed ? `diff-${mark}` : undefined;
        const sign = changed ? <><span aria-hidden="true" className="diff-sign">{mark === 'added' ? '+' : '−'}</span><span className="visually-hidden">{word}</span></>
          : block ? <span aria-hidden="true" className="diff-sign"> </span> : null;
        if (!block) return <span key={i} className={cls}>{sign}{c.value}</span>;
        return c.value.replace(/\n$/, '').split('\n').map((l, j) => <div key={`${i}.${j}`} className={cls}>{sign}{l}</div>);
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
  const [canEdit, setCanEdit] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const [busy, setBusy] = useState(false);
  const [timeZone, setTimeZone] = useState('UTC');
  useEffect(() => {
    let cancelled = false;
    api<Site>('/site').then((s) => !cancelled && setTimeZone(s.timezone), () => {});
    return () => { cancelled = true; };
  }, []);
  const base = `/${kind}/${id}`;
  const back = kind === 'items' ? `/edit/${id}` : `/blocks/${id}`;
  const noun = kind === 'items' ? 'post' : 'block';

  // an item's revision is read at /revisions/{rid} and carries `body`; a block's lives under the block and carries `content`
  const load = async (rid: number): Promise<Full> => {
    if (kind === 'items') return api<Full>(`/revisions/${rid}`);
    const { content, ...r } = await api<Omit<Full, 'body'> & { content: string }>(`${base}/revisions/${rid}`);
    return { ...r, body: content };
  };
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        // the object says whether this user may restore into it (Capabilities decides on the server)
        const [l, obj] = await Promise.all([api<Line[]>(`${base}/revisions`), api<{ canEdit: boolean }>(base)]);
        const [t, f] = await Promise.all([l[0] && load(l[0].id), (l[1] ?? l[0]) && load((l[1] ?? l[0]).id)]);
        if (cancelled) return;
        setMessage(null);
        setList(l);
        setCanEdit(obj.canEdit);
        // newest on top: compare the one before the newest with the newest
        setTo(t ?? null);
        setFrom(f ?? null);
      } catch (e) {
        if (!cancelled) setMessage({ kind: 'alert', text: asApiError(e).status === 403 ? __('You cannot see these revisions.', 'stillpress') : __('Could not load the revisions.', 'stillpress') });
      }
    })();
    return () => { cancelled = true; };
  }, [id, kind]);

  async function pick(side: 'from' | 'to', rid: number) {
    try {
      const r = await load(rid);
      (side === 'from' ? setFrom : setTo)(r);
    } catch { setMessage({ kind: 'alert', text: __('Could not load that revision.', 'stillpress') }); }
  }

  async function restore(rid: number) {
    if (busy || !confirm(__('Restore this revision? The current version stays in the history.', 'stillpress'))) return;
    setBusy(true);
    setMessage(null);
    try {
      const r = await api<{ status?: string; republished?: number }>(`${base}/revisions/${rid}/restore`, { method: 'POST' });
      const notice = kind === 'blocks'
        ? (r.republished ? sprintf(_n('Restored. %d item will be republished.', 'Restored. %d items will be republished.', r.republished, 'stillpress'), r.republished)
          : __('Restored. No published items use this block.', 'stillpress'))
        : r.status === 'publish' ? __('Restored. The site updates in a few seconds.', 'stillpress') : __('Restored.', 'stillpress');
      navigate(back, { state: { notice } });
    } catch (e) {
      setMessage(saveError(asApiError(e), noun));
      setBusy(false);
    }
  }

  const diff = from && to ? compare(from, to) : null;
  const at = (l: Line) => formatInstant(l.createdAt, timeZone);
  const when = (l: Line) => sprintf(__('%1$s by %2$s', 'stillpress'), at(l), l.author ?? __('unknown', 'stillpress'));
  return (
    <Shell>
      <div className="heading-row">
        <h1>{__('Revisions', 'stillpress')}</h1>
        <Link to={back}>{__('Back to the editor', 'stillpress')}</Link>
      </div>
      {message && (
        <div role="alert" className="error">
          <p>{message.text}</p>
          {message.list && <ul>{message.list.map((v) => <li key={v}>{v}</li>)}</ul>}
        </div>
      )}
      {list && (list.length === 0 ? <p className="muted">{__('No revisions yet.', 'stillpress')}</p> : (
        <>
          <table className="table">
            <thead><tr><th>{_x('From', 'revision', 'stillpress')}</th><th>{_x('To', 'revision', 'stillpress')}</th><th>{__('Saved', 'stillpress')}</th>{canEdit && <th />}</tr></thead>
            <tbody>
              {list.map((l) => (
                <tr key={l.id}>
                  <td><input type="radio" name="from" aria-label={sprintf(__('Compare from %s', 'stillpress'), when(l))} checked={from?.id === l.id} onChange={() => pick('from', l.id)} /></td>
                  <td><input type="radio" name="to" aria-label={sprintf(__('Compare to %s', 'stillpress'), when(l))} checked={to?.id === l.id} onChange={() => pick('to', l.id)} /></td>
                  <td>{when(l)}</td>
                  {canEdit && <td><button type="button" disabled={busy} aria-label={sprintf(__('Restore revision from %s', 'stillpress'), at(l))} onClick={() => restore(l.id)}>{__('Restore this revision', 'stillpress')}</button></td>}
                </tr>
              ))}
            </tbody>
          </table>
          {diff && (
            <div className="diff">
              {(['from', 'to'] as const).map((side) => (
                <section key={side} aria-label={side === 'from' ? _x('From', 'revision', 'stillpress') : _x('To', 'revision', 'stillpress')}>
                  <h2>{side === 'from' ? _x('From', 'revision', 'stillpress') : _x('To', 'revision', 'stillpress')}</h2>
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
