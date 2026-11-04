import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { type Block as BlockInstance, serialize } from '@wordpress/blocks';
import { BlockEditorKeyboardShortcuts, BlockEditorProvider } from '@wordpress/block-editor';
import { Button } from '@wordpress/components';
import { store as coreStore } from '@wordpress/core-data';
import { dispatch as wpDispatch } from '@wordpress/data';
import { type ApiError, type ReusableBlock, api } from '../api.ts';
import { bodyOf, installClassicEditor, toBlocks } from './classic.ts';
import {
  EditorContent, EditorFrame, EditorHeader, EditorNotices, type Message, hasClassic, useEditing, useEditorSession,
  useEditorSettings,
} from './workspace.tsx';

const UNSAFE_HTML = 'This block contains HTML your role cannot publish:';

/** The /blocks/:id screen: the pattern editor bound to one reusable block, read-only without the right to edit it. */
export default function BlockEditor() {
  const { id } = useParams();
  const { session, failed } = useEditorSession();
  if (failed) return <main className="page"><p role="alert" className="error">{failed}</p></main>;
  return session && id ? <PatternEditor key={id} id={Number(id)} canPublish={session.role !== 'contributor'} /> : null;
}

function PatternEditor({ id, canPublish }: { id: number; canPublish: boolean }) {
  const navigate = useNavigate();
  const [block, setBlock] = useState<ReusableBlock | null>(null);
  const [title, setTitle] = useState('');
  const [blocks, setBlocks] = useState<BlockInstance[]>([]);
  const [revisions, setRevisions] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const [autosaveAt, setAutosaveAt] = useState<string | null>(null);
  const opened = useRef({ serialized: '', body: '' });
  const latest = useRef({ title, blocks });
  latest.current = { title, blocks };
  const canEdit = !!block?.canEdit;

  const { edits, unsaved, markDirty, blocksChanged, reset, saved: savedEdits } = useEditing(() => canEdit
    ? api(`/blocks/${id}/autosave`, {
      method: 'PUT', body: { title: latest.current.title, content: bodyOf(latest.current.blocks, opened.current) },
    })
    : null);

  useEffect(() => {
    (async () => {
      try {
        const b = await api<ReusableBlock>(`/blocks/${id}`);
        const parsed = toBlocks(b.content ?? '', 'blocks');
        // the Classic block's edit modal needs TinyMCE the moment it opens
        if (hasClassic(parsed)) await installClassicEditor();
        setBlock(b);
        setTitle(b.title);
        setBlocks(parsed);
        opened.current = { serialized: serialize(parsed), body: b.content ?? '' };
        reset(opened.current.serialized);
        setAutosaveAt(b.canEdit ? b.autosave?.createdAt ?? null : null);
        api<unknown[]>(`/blocks/${id}/revisions`).then((r) => setRevisions(r.length), () => {});
      } catch (e) {
        setMessage({ kind: 'alert', text: (e as ApiError).status === 404 ? 'This block does not exist.' : 'Could not load this block.' });
      }
    })();
  }, [id]);

  const changeBlocks = useCallback((b: BlockInstance[]) => {
    setBlocks(b);
    blocksChanged(serialize(b));
  }, [blocksChanged]);

  async function viewAutosave() {
    try {
      const a = await api<{ title: string; content: string }>(`/blocks/${id}/autosave`);
      setTitle(a.title);
      setBlocks(toBlocks(a.content ?? '', 'blocks'));
      markDirty();
      setAutosaveAt(null);
    } catch { setMessage({ kind: 'alert', text: 'Could not load the autosave.' }); }
  }

  async function save() {
    if (!block) return;
    setBusy(true);
    setMessage(null);
    try {
      const mark = edits.current.mark();
      const serialized = serialize(blocks);
      const content = bodyOf(blocks, opened.current);
      const saved = await api<ReusableBlock>(`/blocks/${id}`, {
        method: 'PUT', body: { title, content, expectedModifiedAt: block.modifiedAt },
      });
      setBlock(saved);
      savedEdits(mark, serialized);
      opened.current = { serialized, body: content };
      // the post editor's cached copy of this block, read again when it next shows it
      const core = wpDispatch(coreStore) as unknown as { invalidateResolutionForStoreSelector: (s: string) => void };
      core.invalidateResolutionForStoreSelector('getEntityRecord');
      core.invalidateResolutionForStoreSelector('getEntityRecords');
      setAutosaveAt(null);
      setRevisions((n) => n + 1);
      setMessage({ kind: 'status', text: `Saved. ${saved.usedBy} ${saved.usedBy === 1 ? 'item' : 'items'} will be republished.` });
    } catch (e) {
      const err = e as ApiError;
      setMessage({
        kind: 'alert',
        list: err.error === 'unsafe_html' ? err.violations : undefined,
        text: err.error === 'unsafe_html' ? UNSAFE_HTML
          : err.status === 409 ? 'Someone else changed this block. Reload to see their version; your text stays in this tab.'
          : err.status === 403 ? 'You cannot edit this block.'
          : err.error === 'csrf' ? err.message!
          : 'Could not save. Your text stays in this tab.',
      });
    } finally { setBusy(false); }
  }

  const base = useEditorSettings(canPublish, (ref) => {
    if (!unsaved || confirm('Leave without saving your changes?')) navigate(`/blocks/${ref}`);
  });
  // read-only: the canvas shows the blocks without selecting or editing them
  const settings = useMemo(() => (canEdit ? base : { ...base, isPreviewMode: true, templateLock: 'all' }), [base, canEdit]);

  if (!block) {
    return <main className="page">{message ? <p role="alert" className="error">{message.text}</p> : <p role="status">Loading…</p>}</main>;
  }

  return (
    <EditorFrame>
      <BlockEditorProvider value={blocks} settings={settings} onInput={changeBlocks} onChange={changeBlocks}>
        <BlockEditorKeyboardShortcuts.Register />
        <EditorHeader back="/blocks" label="Reusable blocks" unsaved={unsaved} inserter={canEdit}>
          {canEdit && revisions > 0 && (
            <Button variant="tertiary" onClick={() => navigate(`/blocks/${id}/revisions`)}>Revisions ({revisions})</Button>
          )}
          {canEdit && <Button variant="primary" disabled={busy} onClick={save}>Save</Button>}
        </EditorHeader>
        <div className="sp-editor__main sp-editor__main--single">
          <EditorContent title={title} readOnly={!canEdit} onTitle={(t) => { setTitle(t); markDirty(); }}
            notices={(
              <>
                {!canEdit && (
                  <div role="status" className="sp-notice">
                    You can view this reusable block but not change it; its author or an editor can.
                  </div>
                )}
                <EditorNotices message={message} onDismiss={() => setMessage(null)} autosaveAt={autosaveAt} noun="block"
                  onViewAutosave={viewAutosave} onDismissAutosave={() => setAutosaveAt(null)} />
              </>
            )} />
        </div>
      </BlockEditorProvider>
    </EditorFrame>
  );
}
