import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { type Block as BlockInstance, createBlock, serialize } from '@wordpress/blocks';
import {
  BlockEditorKeyboardShortcuts, BlockEditorProvider, BlockSettingsMenuControls, store as blockEditorStore,
} from '@wordpress/block-editor';
import { Button, MenuItem, Modal, Notice, TextControl } from '@wordpress/components';
import { store as coreStore } from '@wordpress/core-data';
import { dispatch as wpDispatch, select as wpSelect, useDispatch, useRegistry, useSelect } from '@wordpress/data';
import { isInTheFuture } from '@wordpress/date';
import { type ApiError, type Item, type ItemSummary, type SessionUser, type TermView, type UserView, api } from '../api.ts';
import Sidebar, { type Draft } from './Sidebar.tsx';
import { bodyFormatOf, bodyOf, installClassicEditor, toBlocks } from './classic.ts';
import {
  EditorContent, EditorFrame, EditorHeader, EditorNotices, type Message, hasClassic, useEditing, useEditorSession,
  useEditorSettings,
} from './workspace.tsx';

const SAVED: Record<string, string> = {
  publish: 'Published. The site updates in a few seconds.',
  future: 'Scheduled. It goes live at the chosen time.',
  pending: 'Submitted for review.',
  draft: 'Draft saved.',
  private: 'Saved as private.',
};
const FIELD_ERRORS: Record<string, string> = {
  date: 'A scheduled post needs a date in the future.',
  slug: 'This slug cannot be used.',
  parentId: 'Choose another parent page.',
  authorId: 'That author no longer exists.',
};

/** The 400 unsafe_html refusal: the role lacks unfiltered_html, nothing was saved. */
const UNSAFE_HTML = 'This post contains HTML your role cannot publish:';
type WpError = { code?: string; data?: { status?: number; violations?: string[] } };

function draftOf(item: Item, tags: TermView[]): Draft {
  return {
    slug: item.slug ?? '', excerpt: item.excerpt ?? '', visibility: item.status === 'private' ? 'private' : 'public',
    date: item.date, dateChanged: false, categories: item.categories,
    tags: item.tags.map((id) => tags.find((t) => t.id === id)?.name).filter((n): n is string => !!n),
    authorId: item.authorId, commentStatus: item.commentStatus, parentId: item.parentId, menuOrder: item.menuOrder ?? 0,
  };
}

async function allPages(): Promise<{ id: number; title: string }[]> {
  const out: ItemSummary[] = [];
  for (let page = 1; ; page++) {
    const r = await api<{ items: ItemSummary[]; total: number }>(`/items?type=page&page=${page}`);
    out.push(...r.items);
    if (!r.items.length || out.length >= r.total) return out;
  }
}

/** The /edit/:id screen: Gutenberg's block editor bound to one item, with its sidebar and save bar. */
export default function Editor() {
  const { id } = useParams();
  const { session, failed } = useEditorSession();
  if (failed) return <main className="page"><p role="alert" className="error">{failed}</p></main>;
  return session && id ? <ItemEditor key={id} id={Number(id)} session={session} /> : null;
}

function ItemEditor({ id, session }: { id: number; session: SessionUser }) {
  const navigate = useNavigate();
  const [item, setItem] = useState<Item | null>(null);
  const [title, setTitle] = useState('');
  const [blocks, setBlocks] = useState<BlockInstance[]>([]);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [categories, setCategories] = useState<TermView[]>([]);
  const [tags, setTags] = useState<TermView[]>([]);
  const [users, setUsers] = useState<UserView[] | null>(null);
  const [pages, setPages] = useState<{ id: number; title: string }[]>([]);
  const [revisions, setRevisions] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const [autosaveAt, setAutosaveAt] = useState<string | null>(null);
  const opened = useRef({ serialized: '', body: '' });
  const latest = useRef({ title, blocks, excerpt: '', format: item?.bodyFormat });
  latest.current = { title, blocks, excerpt: draft?.excerpt ?? '', format: item?.bodyFormat };

  const role = session.role;
  const editor = role === 'administrator' || role === 'editor';
  const canPublish = role !== 'contributor';

  const dirtyEntities = useSelect((s) => s(coreStore).__experimentalGetDirtyEntityRecords().length > 0, []);
  const { edits, unsaved, markDirty, blocksChanged, reset, saved: savedEdits, autosave } = useEditing(() => {
    const { title: t, blocks: b, excerpt, format } = latest.current;
    // the autosave carries no format; the preview renders it in the item's stored one
    if (bodyFormatOf(b) !== format) return null;
    return api(`/items/${id}/autosave`, { method: 'PUT', body: { title: t, body: bodyOf(b, opened.current), excerpt } });
  }, dirtyEntities);

  useEffect(() => {
    (async () => {
      try {
        const [it, cats, tagList] = await Promise.all([
          api<Item>(`/items/${id}`), api<TermView[]>('/terms?taxonomy=category'), api<TermView[]>('/terms?taxonomy=post_tag'),
        ]);
        setCategories(cats);
        setTags(tagList);
        const parsed = toBlocks(it.body, it.bodyFormat);
        // the Classic block's edit modal needs TinyMCE the moment it opens
        if (it.bodyFormat === 'classic' || hasClassic(parsed)) await installClassicEditor();
        load(it, tagList, parsed);
        setAutosaveAt(it.autosave?.createdAt ?? null);
        if (it.type === 'page') allPages().then(setPages, () => {});
        if (role === 'administrator') api<UserView[]>('/users').then(setUsers, () => {});
        api<unknown[]>(`/items/${id}/revisions`).then((r) => setRevisions(r.length), () => {});
      } catch (e) {
        setMessage({ kind: 'alert', text: (e as ApiError).status === 403 ? 'You cannot edit this item.' : 'Could not load this item.' });
      }
    })();
  }, [id]);

  function load(it: Item, tagList: TermView[], parsed: BlockInstance[]) {
    setItem(it);
    setTitle(it.title);
    setBlocks(parsed);
    setDraft(draftOf(it, tagList));
    opened.current = { serialized: serialize(parsed), body: it.body };
    reset(opened.current.serialized);
  }

  const changeBlocks = useCallback((b: BlockInstance[]) => {
    setBlocks(b);
    blocksChanged(serialize(b));
  }, [blocksChanged]);

  async function viewAutosave() {
    try {
      const a = await api<{ title: string; body: string; excerpt: string }>(`/items/${id}/autosave`);
      setTitle(a.title);
      setBlocks(toBlocks(a.body, latest.current.format ?? 'blocks'));
      setDraft((d) => d && { ...d, excerpt: a.excerpt ?? '' });
      markDirty();
      setAutosaveAt(null);
    } catch { setMessage({ kind: 'alert', text: 'Could not load the autosave.' }); }
  }

  async function preview() {
    // opened before the autosave so the popup blocker sees the click
    const tab = window.open('about:blank', '_blank');
    try {
      await autosave(true);
    } catch (e) {
      const err = e as ApiError;
      // the preview would show the older version; other failures open it as before
      if (err.error === 'unsafe_html') {
        tab?.close();
        setMessage({ kind: 'alert', text: UNSAFE_HTML, list: err.violations });
        return;
      }
    }
    if (tab) tab.location.href = `/admin/preview/${id}`;
  }

  async function addCategory(name: string, parentId: number | null) {
    try {
      const t = await api<TermView>('/terms', { method: 'POST', body: { taxonomy: 'category', name, parentId } });
      setCategories((c) => [...c, t]);
      patch({ categories: [...(draft?.categories ?? []), t.id] });
    } catch { setMessage({ kind: 'alert', text: 'Could not add the category.' }); }
  }

  function patch(p: Partial<Draft>) {
    setDraft((d) => d && { ...d, ...p });
    markDirty();
  }

  async function tagIds(names: string[]): Promise<number[]> {
    const ids: number[] = [];
    let list = tags;
    for (const name of names) {
      const known = list.find((t) => t.name.toLowerCase() === name.toLowerCase());
      if (known) ids.push(known.id);
      else if (canPublish) {
        const t = await api<TermView>('/terms', { method: 'POST', body: { taxonomy: 'post_tag', name, parentId: null } });
        list = [...list, t];
        ids.push(t.id);
      }
    }
    setTags(list);
    return ids;
  }

  async function save(status: string) {
    if (!item || !draft) return;
    setBusy(true);
    setMessage(null);
    try {
      // edited reusable blocks first: a post that shows them must not go out without them
      for (const r of wpSelect(coreStore).__experimentalGetDirtyEntityRecords()) {
        try {
          await wpDispatch(coreStore).saveEditedEntityRecord(r.kind, r.name, r.key, { throwOnError: true });
        } catch (e) {
          const err = e as WpError;
          setMessage(err.code === 'unsafe_html'
            ? { kind: 'alert', text: 'A reusable block in this post contains HTML your role cannot publish; the post was not saved:', list: err.data?.violations }
            : { kind: 'alert', text: err.data?.status === 403
              ? 'You cannot edit reusable blocks; the post was not saved'
              : 'A reusable block could not be saved; the post was not saved.' });
          return;
        }
      }
      const mark = edits.current.mark();
      const serialized = serialize(blocks);
      const body = bodyOf(blocks, opened.current);
      const saved = await api<Item>(`/items/${id}`, {
        method: 'PUT',
        body: {
          title, slug: draft.slug, body, bodyFormat: bodyFormatOf(blocks),
          excerpt: draft.excerpt, status, localDate: draft.dateChanged ? draft.date : undefined,
          categories: item.type === 'post' ? draft.categories : undefined,
          tags: item.type === 'post' ? await tagIds(draft.tags) : undefined,
          authorId: editor ? draft.authorId : undefined, commentStatus: draft.commentStatus,
          // 0 clears the parent; null would keep it
          parentId: item.type === 'page' ? draft.parentId ?? 0 : undefined, menuOrder: item.type === 'page' ? draft.menuOrder : undefined,
          expectedModifiedAt: item.modifiedAt,
        },
      });
      setItem(saved);
      savedEdits(mark, serialized);
      opened.current = { serialized, body };
      // sidebar changes made while the save was in flight stay in the form
      if (!edits.current.dirty) setDraft((d) => d && { ...draftOf(saved, tags), tags: d.tags });
      setAutosaveAt(null);
      setRevisions((n) => n + 1);
      setMessage({ kind: 'status', text: SAVED[saved.status] ?? 'Saved.' });
    } catch (e) {
      const err = e as ApiError;
      setMessage({
        kind: 'alert',
        list: err.error === 'unsafe_html' ? err.violations : undefined,
        text: err.error === 'unsafe_html' ? UNSAFE_HTML
          : err.status === 409 ? 'Someone else changed this post. Reload to see their version; your text stays in this tab.'
          : err.status === 403 ? 'You cannot make this change.'
          : err.error === 'csrf' ? err.message!
          : err.field ? FIELD_ERRORS[err.field] ?? `Check the ${err.field} field.`
          : 'Could not save. Your text stays in this tab.',
      });
    } finally { setBusy(false); }
  }

  const settings = useEditorSettings(canPublish, (ref) => {
    // synced patterns are edited on their own screen, as in WordPress
    if (!unsaved || confirm('Leave without saving your changes?')) navigate(`/blocks/${ref}`);
  });

  if (!item || !draft) {
    return <main className="page">{message ? <p role="alert" className="error">{message.text}</p> : <p role="status">Loading…</p>}</main>;
  }

  const published = ['publish', 'future', 'private'].includes(item.status);
  const future = !!draft.date && isInTheFuture(draft.date);
  const target = draft.visibility === 'private' ? 'private' : 'publish';
  const back = item.type === 'page' ? '/pages' : '/posts';

  return (
    <EditorFrame>
      <BlockEditorProvider value={blocks} settings={settings} onInput={changeBlocks} onChange={changeBlocks}>
        <BlockEditorKeyboardShortcuts.Register />
        <EditorHeader back={back} label={item.type === 'page' ? 'Pages' : 'Posts'} unsaved={unsaved}>
          {revisions > 0 && <Button variant="tertiary" onClick={() => navigate(`/edit/${id}/revisions`)}>Revisions ({revisions})</Button>}
          {bodyFormatOf(blocks) === item.bodyFormat && <Button variant="tertiary" onClick={preview}>Preview</Button>}
          {!published && <Button variant="secondary" disabled={busy} onClick={() => save('draft')}>Save draft</Button>}
          {!canPublish && (
            <Button variant="primary" disabled={busy} onClick={() => save('pending')}>Submit for review</Button>
          )}
          {canPublish && (
            <Button variant="primary" disabled={busy} onClick={() => save(target)}>
              {published ? (future && item.status !== 'future' ? 'Schedule' : 'Update') : future ? 'Schedule' : 'Publish'}
            </Button>
          )}
        </EditorHeader>
        <div className="sp-editor__main">
          <EditorContent title={title} onTitle={(t) => { setTitle(t); markDirty(); }}
            notices={<EditorNotices message={message} onDismiss={() => setMessage(null)} autosaveAt={autosaveAt} noun="post"
              onViewAutosave={viewAutosave} onDismissAutosave={() => setAutosaveAt(null)} />} />
          <aside className="sp-editor__sidebar" aria-label="Settings">
            <Sidebar item={item} session={session} draft={draft} onChange={patch} categories={categories}
              onAddCategory={addCategory} tags={tags} users={users} pages={pages} />
          </aside>
        </div>
        {canPublish && <MakeReusable />}
      </BlockEditorProvider>
    </EditorFrame>
  );
}

/** "Create reusable block" in a block's options menu; the selection becomes a reference to the new block. */
function MakeReusable() {
  const [pending, setPending] = useState<string[] | null>(null);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const registry = useRegistry();
  const { replaceBlocks } = useDispatch(blockEditorStore);
  const { saveEntityRecord } = useDispatch(coreStore);
  // the provider keeps its blocks in its own registry, so the global select would not see them
  const blocksOf = (ids: string[]) => (registry.select(blockEditorStore).getBlocksByClientId(ids) as (BlockInstance | null)[])
    .filter((b): b is BlockInstance => !!b);
  async function create() {
    if (!pending || !name.trim()) return;
    try {
      const content = serialize(blocksOf(pending));
      const rec = await saveEntityRecord('postType', 'wp_block', { title: name.trim(), content, status: 'publish' },
        { throwOnError: true }) as { id: number };
      replaceBlocks(pending, createBlock('core/block', { ref: rec.id }));
      setPending(null);
      setName('');
    } catch (e) {
      const err = e as WpError;
      setError(err.code === 'unsafe_html' ? `This block contains HTML your role cannot publish: ${err.data?.violations?.join(', ')}`
        : err.data?.status === 403 ? 'You cannot create reusable blocks.' : 'Could not create the reusable block.');
    }
  }
  return (
    <>
      <BlockSettingsMenuControls>
        {({ selectedClientIds, onClose }) => {
          const all = blocksOf(selectedClientIds ?? []);
          if (!all.length || all.some((b) => b.name === 'core/block')) return null;
          return <MenuItem onClick={() => { setError(null); setPending(selectedClientIds); onClose?.(); }}>Create reusable block</MenuItem>;
        }}
      </BlockSettingsMenuControls>
      {pending && (
        <Modal title="Create reusable block" onRequestClose={() => setPending(null)}>
          <form onSubmit={(e) => { e.preventDefault(); create(); }}>
            <TextControl __next40pxDefaultSize label="Name" value={name} onChange={setName} />
            {error && <Notice status="error" isDismissible={false}><span role="alert">{error}</span></Notice>}
            <div className="sp-modal-actions">
              <Button variant="tertiary" onClick={() => setPending(null)}>Cancel</Button>
              <Button variant="primary" type="submit" disabled={!name.trim()}>Create</Button>
            </div>
          </form>
        </Modal>
      )}
    </>
  );
}
