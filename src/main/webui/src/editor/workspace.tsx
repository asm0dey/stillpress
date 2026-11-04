import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import '@wordpress/format-library';
import { registerCoreBlocks } from '@wordpress/block-library';
import { type Block as BlockInstance } from '@wordpress/blocks';
import { BlockCanvas, BlockList, Inserter } from '@wordpress/block-editor';
import { Button, Popover, SlotFillProvider, SnackbarList } from '@wordpress/components';
import { store as coreStore } from '@wordpress/core-data';
import { useDispatch, useSelect } from '@wordpress/data';
import { store as noticesStore } from '@wordpress/notices';
import { getSettings, setSettings } from '@wordpress/date';
import { type ApiError, type ItemSummary, type SessionUser, type Site, api } from '../api.ts';
import { installWordPressApi } from './wpapi.ts';
import { picked, upload, uploadError } from './MediaLibrary.tsx';
import { Edits } from './edits.ts';
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

/** What the post editor and the reusable block editor share: canvas, frame, header, notices, autosave and dirty state. */

registerCoreBlocks();

/** The canvas iframe's stylesheets, as WordPress hands them over in __unstableResolvedAssets. */
const CANVAS_ASSETS = { styles: `<style>${componentsCss}${contentCss}${blocksCss}${blocksEditorCss}${blocksThemeCss}</style>`, scripts: '' };
const CANVAS_STYLES = [{ css: 'body { font-family: Georgia, serif; font-size: 1.125rem; line-height: 1.6; padding: 24px; }' }];
const AUTOSAVE_MS = 60_000;

export const hasClassic = (blocks: BlockInstance[]): boolean =>
  blocks.some((b) => b.name === 'core/freeform' || hasClassic(b.innerBlocks));

export interface Message { kind: 'status' | 'alert'; text: string; list?: string[] }

type Noun = 'post' | 'block';

/** The 400 unsafe_html refusal: the role lacks unfiltered_html, nothing was saved. */
export const UNSAFE_HTML = (noun: Noun) => `This ${noun} contains HTML your role cannot publish:`;

const FIELD_ERRORS: Record<string, string> = {
  date: 'A scheduled post needs a date in the future.',
  slug: 'This slug cannot be used.',
  parentId: 'Choose another parent page.',
  authorId: 'That author no longer exists.',
};

/** What a refused save tells the user. */
export function saveError(err: ApiError, noun: Noun): Message {
  return {
    kind: 'alert',
    list: err.error === 'unsafe_html' ? err.violations : undefined,
    text: err.error === 'unsafe_html' ? UNSAFE_HTML(noun)
      : err.status === 409 ? `Someone else changed this ${noun}. Reload to see their version; your text stays in this tab.`
      : err.status === 403 ? 'You cannot make this change.'
      : err.error === 'csrf' ? err.message!
      : err.field ? FIELD_ERRORS[err.field] ?? `Check the ${err.field} field.`
      : 'Could not save. Your text stays in this tab.',
  };
}

let installedFor: number | null = null;

/** The session, once the site's time zone and the WordPress API translator are in place. */
export function useEditorSession(): { session: SessionUser | null; failed: string | null } {
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
  return { session, failed };
}

async function fetchLinkSuggestions(search: string) {
  const lists = await Promise.all(['post', 'page'].map((t) =>
    api<{ items: ItemSummary[] }>(`/items?type=${t}&status=publish&search=${encodeURIComponent(search)}`)));
  return lists.flatMap((l) => l.items).filter((i) => i.permalink)
    .map((i) => ({ id: i.id, url: i.permalink!, title: i.title || '(no title)', type: i.type, kind: 'post-type' }));
}

/** The block editor settings; a reusable block's "Edit original" opens its own screen, as in WordPress. */
export function useEditorSettings(canPublish: boolean, unsaved: boolean) {
  const navigate = useNavigate();
  const onEditBlock = (ref: number) => {
    if (!unsaved || confirm('Leave without saving your changes?')) navigate(`/blocks/${ref}`);
  };
  const reusable = useSelect((s) => s(coreStore).getEntityRecords('postType', 'wp_block',
    { per_page: -1 }) as { id: number; title: { raw: string }; content: { raw: string } }[] | null, []);
  const onEdit = useRef(onEditBlock);
  onEdit.current = onEditBlock;
  return useMemo(() => ({
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
      if (postType === 'wp_block') onEdit.current(Number(postId));
    },
  }), [canPublish, reusable]);
}

/**
 * Unsaved edits, the leave warning, the 60 s autosave and the notices. {@code put} sends the autosave, or returns null
 * when there is nothing it can send.
 */
export function useEditing(put: () => Promise<unknown> | null) {
  const edits = useRef(new Edits(''));
  const [dirty, setDirty] = useState(false);
  const [message, setMessage] = useState<Message | null>(null);
  const [autosaveAt, setAutosaveAt] = useState<string | null>(null);
  const send = useRef(put);
  send.current = put;

  const markDirty = useCallback(() => {
    edits.current.edit();
    setDirty(true);
  }, []);
  const blocksChanged = useCallback((serialized: string) => {
    edits.current.blocks(serialized);
    setDirty(edits.current.dirty);
  }, []);
  const reset = useCallback((serialized: string) => {
    edits.current = new Edits(serialized);
    setDirty(false);
  }, []);
  /** After a save: {@code dirty} again only for edits made while it was in flight. */
  const saved = useCallback((mark: number, serialized: string) => {
    edits.current.saved(mark, serialized);
    setDirty(edits.current.dirty);
  }, []);

  const unsaved = dirty;
  useEffect(() => {
    if (!unsaved) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [unsaved]);

  /** {@code explicit}: a Preview click, which must hear about a refusal; the timer stays silent. */
  const autosave = useCallback(async (explicit = false) => {
    if (!edits.current.needsAutosave) return;
    const mark = edits.current.mark();
    const request = send.current();
    if (!request) return;
    try {
      await request;
      edits.current.autosaved(mark);
    } catch (e) {
      if (explicit) throw e;
      /* retried at the next tick */
    }
  }, []);

  useEffect(() => {
    const timer = setInterval(() => autosave(), AUTOSAVE_MS);
    return () => clearInterval(timer);
  }, [autosave]);

  /** "View the autosave": {@code apply} puts the autosave's fields into the editor. */
  async function viewAutosave<T>(path: string, apply: (a: T) => void) {
    try {
      apply(await api<T>(path));
      markDirty();
      setAutosaveAt(null);
    } catch { setMessage({ kind: 'alert', text: 'Could not load the autosave.' }); }
  }

  return {
    edits, unsaved, markDirty, blocksChanged, reset, saved, autosave, viewAutosave,
    message, setMessage, autosaveAt, setAutosaveAt,
  };
}

/** The screen around a block editor: slots for popovers and the block library's snackbars. */
export function EditorFrame({ children }: { children: ReactNode }) {
  return (
    <SlotFillProvider>
      <div className="sp-editor">
        {children}
        <Popover.Slot />
        <Snackbars />
      </div>
    </SlotFillProvider>
  );
}

/** The top bar: a way back that warns about unsaved edits, the inserter, and the save buttons. */
export function EditorHeader({ back, label, unsaved, inserter = true, children }: {
  back: string; label: string; unsaved: boolean; inserter?: boolean; children: ReactNode;
}) {
  return (
    <header className="sp-editor__bar">
      <Link to={back} onClick={(e) => { if (unsaved && !confirm('Leave without saving your changes?')) e.preventDefault(); }}>
        ← {label}
      </Link>
      {inserter && <Inserter />}
      <div className="sp-editor__actions">{children}</div>
    </header>
  );
}

/** The save result or error, and the offer to load a newer autosave. */
export function EditorNotices({ message, onDismiss, autosaveAt, noun, onViewAutosave, onDismissAutosave }: {
  message: Message | null; onDismiss: () => void; autosaveAt: string | null; noun: string;
  onViewAutosave: () => void; onDismissAutosave: () => void;
}) {
  return (
    <>
      {message && (
        <div role={message.kind} className={message.kind === 'alert' ? 'sp-notice sp-notice--error' : 'sp-notice'}>
          {message.text}
          {message.list && <ul>{message.list.map((v) => <li key={v}>{v}</li>)}</ul>}
          {message.kind === 'alert' && <Button variant="link" onClick={onDismiss}>Dismiss</Button>}
        </div>
      )}
      {autosaveAt && (
        <div role="status" className="sp-notice">
          There is an autosave of this {noun} that is more recent than the version below.{' '}
          <Button variant="link" onClick={onViewAutosave}>View the autosave</Button>{' '}
          <Button variant="link" onClick={onDismissAutosave}>Dismiss</Button>
        </div>
      )}
    </>
  );
}

/** Notices, the title field and the canvas. */
export function EditorContent({ notices, title, onTitle, readOnly = false }: {
  notices: ReactNode; title: string; onTitle: (t: string) => void; readOnly?: boolean;
}) {
  return (
    <div className="sp-editor__content">
      {notices}
      <label className="sp-visually-hidden" htmlFor="sp-title">Title</label>
      <input id="sp-title" className="sp-title" placeholder="Add title" value={title} readOnly={readOnly}
        onChange={(e) => onTitle(e.target.value)} />
      <Canvas />
    </div>
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

/** The block library's own notices (upload errors, "Copied"). */
function Snackbars() {
  const notices = useSelect((s) => s(noticesStore).getNotices(), []);
  const { removeNotice } = useDispatch(noticesStore);
  return <SnackbarList className="sp-snackbars" notices={notices} onRemove={removeNotice} />;
}
