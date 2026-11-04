import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import '@wordpress/format-library';
import { registerCoreBlocks } from '@wordpress/block-library';
import { type Block as BlockInstance, createBlock, serialize } from '@wordpress/blocks';
import {
  BlockCanvas, BlockEditorKeyboardShortcuts, BlockEditorProvider, BlockList, BlockSettingsMenuControls, Inserter,
  store as blockEditorStore,
} from '@wordpress/block-editor';
import { Button, MenuItem, Modal, Notice, Popover, SlotFillProvider, SnackbarList, TextControl } from '@wordpress/components';
import { store as coreStore, useEntityBlockEditor } from '@wordpress/core-data';
import { dispatch as wpDispatch, select as wpSelect, useDispatch, useRegistry, useSelect } from '@wordpress/data';
import { store as noticesStore } from '@wordpress/notices';
import { getSettings, isInTheFuture, setSettings } from '@wordpress/date';
import { type ApiError, type Item, type ItemSummary, type SessionUser, type Site, type TermView, type UserView, api } from '../api.ts';
import { installWordPressApi } from './wpapi.ts';
import { picked, upload, uploadError } from './MediaLibrary.tsx';
import Sidebar, { type Draft } from './Sidebar.tsx';
import { Edits } from './edits.ts';
import { bodyFormatOf, bodyOf, installClassicEditor, toBlocks } from './classic.ts';
import '@wordpress/components/build-style/style.css';
import '@wordpress/block-editor/build-style/style.css';
// format-library does not export its stylesheet
import '../../node_modules/@wordpress/format-library/build-style/style.css';
import componentsCss from '@wordpress/components/build-style/style.css?inline';
import contentCss from '@wordpress/block-editor/build-style/content.css?inline';
import blocksCss from '@wordpress/block-library/build-style/style.css?inline';
import blocksEditorCss from '@wordpress/block-library/build-style/editor.css?inline';
import blocksThemeCss from '@wordpress/block-library/build-style/theme.css?inline';
import './editor.css';

registerCoreBlocks();

/** The canvas iframe's stylesheets, as WordPress hands them over in __unstableResolvedAssets. */
const CANVAS_ASSETS = { styles: `<style>${componentsCss}${contentCss}${blocksCss}${blocksEditorCss}${blocksThemeCss}</style>`, scripts: '' };
const CANVAS_STYLES = [{ css: 'body { font-family: Georgia, serif; font-size: 1.125rem; line-height: 1.6; padding: 24px; }' }];
const AUTOSAVE_MS = 60_000;

const hasClassic = (blocks: BlockInstance[]): boolean =>
  blocks.some((b) => b.name === 'core/freeform' || hasClassic(b.innerBlocks));

let installedFor: number | null = null;

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

interface Message { kind: 'status' | 'alert'; text: string }

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

async function fetchLinkSuggestions(search: string) {
  const lists = await Promise.all(['post', 'page'].map((t) =>
    api<{ items: ItemSummary[] }>(`/items?type=${t}&status=publish&search=${encodeURIComponent(search)}`)));
  return lists.flatMap((l) => l.items).filter((i) => i.permalink)
    .map((i) => ({ id: i.id, url: i.permalink!, title: i.title || '(no title)', type: i.type, kind: 'post-type' }));
}

/** The /edit/:id screen: Gutenberg's block editor bound to one item, with its sidebar and save bar. */
export default function Editor() {
  const { id } = useParams();
  const [session, setSession] = useState<SessionUser | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  useEffect(() => {
    Promise.all([api<SessionUser>('/session'), api<Site>('/site')]).then(([s, site]) => {
      const date = getSettings();
      setSettings({ ...date, timezone: { ...date.timezone, string: site.timezone } });
      if (installedFor !== s.id) {
        installWordPressApi(s);
        installedFor = s.id;
      }
      setSession(s);
    }, () => setFailed('Could not load the editor.'));
  }, []);
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
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const [autosaveAt, setAutosaveAt] = useState<string | null>(null);
  const [editingRef, setEditingRef] = useState<number | null>(null);
  const edits = useRef(new Edits(''));
  const opened = useRef({ serialized: '', body: '' });
  const latest = useRef({ title, blocks, excerpt: '', format: item?.bodyFormat });
  latest.current = { title, blocks, excerpt: draft?.excerpt ?? '', format: item?.bodyFormat };

  const role = session.role;
  const editor = role === 'administrator' || role === 'editor';
  const canPublish = role !== 'contributor';

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
    edits.current = new Edits(opened.current.serialized);
    setDirty(false);
  }

  const markDirty = useCallback(() => {
    edits.current.edit();
    setDirty(true);
  }, []);

  const changeBlocks = useCallback((b: BlockInstance[]) => {
    setBlocks(b);
    edits.current.blocks(serialize(b));
    setDirty(edits.current.dirty);
  }, []);

  const dirtyEntities = useSelect((s) => s(coreStore).__experimentalGetDirtyEntityRecords().length > 0, []);
  const unsaved = dirty || dirtyEntities;

  useEffect(() => {
    if (!unsaved) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [unsaved]);

  // a Classic block inserted into a blocks post
  useEffect(() => { if (hasClassic(blocks)) installClassicEditor(); }, [blocks]);

  const autosave = useCallback(async () => {
    const { title: t, blocks: b, excerpt, format } = latest.current;
    // the autosave carries no format; the preview renders it in the item's stored one
    if (!edits.current.needsAutosave || bodyFormatOf(b) !== format) return;
    const mark = edits.current.mark();
    try {
      await api(`/items/${id}/autosave`, { method: 'PUT', body: { title: t, body: bodyOf(b, opened.current), excerpt } });
      edits.current.autosaved(mark);
    } catch { /* retried at the next tick */ }
  }, [id]);

  useEffect(() => {
    const timer = setInterval(autosave, AUTOSAVE_MS);
    return () => clearInterval(timer);
  }, [autosave]);

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
    await autosave();
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
          setMessage({ kind: 'alert', text: (e as { data?: { status?: number } })?.data?.status === 403
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
      edits.current.saved(mark, serialized);
      opened.current = { serialized, body };
      // sidebar changes made while the save was in flight stay in the form
      if (!edits.current.dirty) setDraft((d) => d && { ...draftOf(saved, tags), tags: d.tags });
      setDirty(edits.current.dirty);
      setAutosaveAt(null);
      setRevisions((n) => n + 1);
      setMessage({ kind: 'status', text: SAVED[saved.status] ?? 'Saved.' });
    } catch (e) {
      const err = e as ApiError;
      setMessage({
        kind: 'alert',
        text: err.status === 409 ? 'Someone else changed this post. Reload to see their version; your text stays in this tab.'
          : err.status === 403 ? 'You cannot make this change.'
          : err.error === 'csrf' ? err.message!
          : err.field ? FIELD_ERRORS[err.field] ?? `Check the ${err.field} field.`
          : 'Could not save. Your text stays in this tab.',
      });
    } finally { setBusy(false); }
  }

  const reusable = useSelect((s) => s(coreStore).getEntityRecords('postType', 'wp_block',
    { per_page: -1 }) as { id: number; title: { raw: string }; content: { raw: string } }[] | null, []);

  const settings = useMemo(() => ({
    hasFixedToolbar: false,
    alignWide: true,
    mediaUpload: canPublish ? ({ filesList, onFileChange, onError }: {
      filesList: FileList | File[]; onFileChange: (m: unknown[]) => void; onError?: (message: string) => void;
    }) => {
      upload(Array.from(filesList)).then((m) => onFileChange(m.map(picked)), (e) => onError?.(uploadError(e)));
    } : undefined,
    __experimentalFetchLinkSuggestions: fetchLinkSuggestions,
    __experimentalFeatures: { layout: { contentSize: '650px', wideSize: '1200px' }, spacing: { blockGap: true } },
    __experimentalReusableBlocks: reusable ?? [],
    __experimentalBlockPatterns: [],
    __unstableResolvedAssets: CANVAS_ASSETS,
    // the Classic block copies these into TinyMCE's iframe
    styles: CANVAS_STYLES,
    onNavigateToEntityRecord: ({ postId, postType }: { postId: string | number; postType: string }) => {
      if (postType === 'wp_block') setEditingRef(Number(postId));
    },
  }), [canPublish, reusable]);

  if (!item || !draft) {
    return <main className="page">{message ? <p role="alert" className="error">{message.text}</p> : <p role="status">Loading…</p>}</main>;
  }

  const published = ['publish', 'future', 'private'].includes(item.status);
  const future = !!draft.date && isInTheFuture(draft.date);
  const target = draft.visibility === 'private' ? 'private' : 'publish';
  const back = item.type === 'page' ? '/pages' : '/posts';

  const saveBar = (
    <div className="sp-editor__actions">
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
    </div>
  );

  const notices = (
    <>
      {message && (
        <div role={message.kind} className={message.kind === 'alert' ? 'sp-notice sp-notice--error' : 'sp-notice'}>
          {message.text}
          {message.kind === 'alert' && <Button variant="link" onClick={() => setMessage(null)}>Dismiss</Button>}
        </div>
      )}
      {autosaveAt && (
        <div role="status" className="sp-notice">
          There is an autosave of this post that is more recent than the version below.{' '}
          <Button variant="link" onClick={viewAutosave}>View the autosave</Button>{' '}
          <Button variant="link" onClick={() => setAutosaveAt(null)}>Dismiss</Button>
        </div>
      )}
    </>
  );

  const header = (
    <header className="sp-editor__bar">
      <Link to={back} onClick={(e) => { if (unsaved && !confirm('Leave without saving your changes?')) e.preventDefault(); }}>
        ← {item.type === 'page' ? 'Pages' : 'Posts'}
      </Link>
      <Inserter />
      {saveBar}
    </header>
  );

  const sidebar = (
    <aside className="sp-editor__sidebar" aria-label="Settings">
      <Sidebar item={item} session={session} draft={draft} onChange={patch} categories={categories}
        onAddCategory={addCategory} tags={tags} users={users} pages={pages} />
    </aside>
  );

  return (
    <SlotFillProvider>
      <div className="sp-editor">
        {editingRef !== null ? (
          <ReusableWorkspace id={editingRef} settings={settings} onBack={() => setEditingRef(null)}
            header={header} sidebar={sidebar} notices={notices} />
        ) : (
          <BlockEditorProvider value={blocks} settings={settings}
            onInput={changeBlocks} onChange={changeBlocks}>
            <BlockEditorKeyboardShortcuts.Register />
            {header}
            <div className="sp-editor__main">
              <div className="sp-editor__content">
                {notices}
                <label className="sp-visually-hidden" htmlFor="sp-title">Title</label>
                <input id="sp-title" className="sp-title" placeholder="Add title" value={title}
                  onChange={(e) => { setTitle(e.target.value); markDirty(); }} />
                <Canvas />
              </div>
              {sidebar}
            </div>
            {canPublish && <MakeReusable />}
          </BlockEditorProvider>
        )}
        <Popover.Slot />
        <Snackbars />
      </div>
    </SlotFillProvider>
  );
}

function Canvas() {
  return (
    <div className="sp-canvas">
      <BlockCanvas height="100%" styles={CANVAS_STYLES}>
        <BlockList layout={{ type: 'constrained' }} />
      </BlockCanvas>
    </div>
  );
}

/** "Edit original" from a post: the reusable block's own blocks, held in core-data until the post is saved. */
function ReusableWorkspace({ id, settings, onBack, header, sidebar, notices }: {
  id: number; settings: Record<string, unknown>; onBack: () => void; header: ReactNode; sidebar: ReactNode; notices: ReactNode;
}) {
  const [blocks, onInput, onChange] = useEntityBlockEditor('postType', 'wp_block', { id });
  const title = useSelect((s) => (s(coreStore).getEditedEntityRecord('postType', 'wp_block', id) as { title?: string })?.title, [id]);
  return (
    <BlockEditorProvider value={blocks} onInput={onInput} onChange={onChange} settings={settings}>
      {header}
      <div className="sp-editor__main">
        <div className="sp-editor__content">
          {notices}
          <div role="status" className="sp-notice">
            Editing the reusable block “{title}”. Every post that shows it changes when you save this post.{' '}
            <Button variant="link" onClick={onBack}>Back to the post</Button>
          </div>
          <Canvas />
        </div>
        {sidebar}
      </div>
    </BlockEditorProvider>
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
      setError((e as { data?: { status?: number } })?.data?.status === 403 ? 'You cannot create reusable blocks.' : 'Could not create the reusable block.');
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

/** The block library's own notices (upload errors, "Copied"). */
function Snackbars() {
  const notices = useSelect((s) => s(noticesStore).getNotices(), []);
  const { removeNotice } = useDispatch(noticesStore);
  return <SnackbarList className="sp-snackbars" notices={notices} onRemove={removeNotice} />;
}
