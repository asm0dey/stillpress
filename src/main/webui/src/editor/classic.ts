import {
  type Block as BlockInstance, __unstableSerializeAndClean, createBlock, parse, serialize, setFreeformContentHandlerName,
} from '@wordpress/blocks';
import { __ } from '@wordpress/i18n';
import { locale } from '../i18n/index.ts';
import { autop } from '@wordpress/autop';
import { addFilter } from '@wordpress/hooks';
import { type ComponentType, createElement, useEffect, useState } from 'react';
import type { RawEditorOptions, TinyMCE } from 'tinymce';

// HTML outside block delimiters is a Classic block and serializes without delimiters, as in WordPress. Its
// registerCoreBlocks sets this only when window.wp.oldEditor exists at that moment; ours is installed lazily.
setFreeformContentHandlerName('core/freeform');

export type BodyFormat = 'classic' | 'blocks';

/**
 * An item's body as editor blocks: classic HTML is one Classic block, never converted (spec §10). Its content gets
 * paragraphs as WordPress's parser gives it, so TinyMCE and "Convert to blocks" see them.
 */
export function toBlocks(body: string, bodyFormat: BodyFormat): BlockInstance[] {
  return bodyFormat === 'classic' ? [createBlock('core/freeform', { content: autop(body).trim() })] : parse(body);
}

/**
 * The body to store. While the blocks serialize as they did when opened, the item's own bytes: autop and removep are
 * not exact inverses, so a title-only save must not rewrite the text. Otherwise WordPress's post serialization,
 * which strips the paragraphs from a lone Classic block again.
 */
export function bodyOf(blocks: BlockInstance[], opened: { serialized: string; body: string }): string {
  return serialize(blocks) === opened.serialized ? opened.body : __unstableSerializeAndClean(blocks);
}

/** `classic` while the document is only Classic blocks, whose serialization is their raw HTML. */
export function bodyFormatOf(blocks: BlockInstance[]): BodyFormat {
  return blocks.length > 0 && blocks.every((b) => b.name === 'core/freeform') ? 'classic' : 'blocks';
}

// WordPress's classic-editor toolbars without its PHP-shipped wp* buttons
export const SETTINGS = {
  // TinyMCE is GPL-2.0-or-later since 7.0, as Stillpress is; without a key the editor opens read-only
  license_key: 'gpl',
  // bundled through Resource.add by the skin and content modules load() imports; nothing is fetched by URL
  skin: 'oxide',
  content_css: 'default',
  menubar: false,
  branding: false,
  promotion: false,
  // paste, hr, textcolor and colorpicker are core since 6; tabfocus is gone
  plugins: 'charmap link lists media fullscreen',
  toolbar1: 'blocks bold italic bullist numlist blockquote alignleft aligncenter alignright link unlink fullscreen',
  toolbar2: 'strikethrough hr forecolor pastetext removeformat charmap outdent indent undo redo',
  relative_urls: false,
  convert_urls: false,
  entity_encoding: 'raw',
  // ruling R24: editors and administrators keep everything (WordPress's unfiltered_html); Task 8b refuses
  // script-capable HTML from authors and contributors on the server
  // old posts carry iframes, embeds, scripts and event attributes; TinyMCE's schema would drop what the user never
  // touched (inside the editor TinyMCE keeps scripts inert)
  valid_elements: '*[*]',
  // TinyMCE 6+ defaults that would rewrite untouched markup on the first edit, for the same R24 reason:
  // DOMPurify would drop commented-out HTML, scripts inside <svg> and namespaced attributes (xlink:href),
  xss_sanitization: false,
  // every <iframe> would gain sandbox="",
  sandbox_iframes: false,
  // <object>/<embed> would become <iframe>/<img>/<video>,
  convert_unsafe_embeds: false,
  // javascript: URLs would be dropped,
  allow_script_urls: true,
  // target="_blank" links would gain rel="noopener",
  allow_unsafe_link_target: true,
  // and <br />, <img … /> would lose their slash (6+ writes html; WordPress's classic HTML is xhtml)
  element_format: 'xhtml',
} satisfies RawEditorOptions;

declare global {
  interface Window {
    tinymce?: TinyMCE;
    wpEditorL10n?: { tinymce: { baseURL: string; suffix: string; settings: Record<string, unknown> } };
    wp?: { oldEditor?: unknown };
  }
}

let installed: Promise<void> | null = null;
let ready = false;

// core/freeform's edit modal reads wp.oldEditor the moment it opens, so a Classic block that appears after load
// (inserter, autosave, revision, reusable block) shows a placeholder until TinyMCE is installed
export const withClassicEditor = (BlockEdit: ComponentType<{ name: string }>) =>
  function ClassicReady(props: { name: string }) {
    const [state, setState] = useState<'loading' | 'ready' | 'failed'>(ready ? 'ready' : 'loading');
    const classic = props.name === 'core/freeform';
    useEffect(() => {
      if (classic && state === 'loading') installClassicEditor().then(() => setState('ready'), () => setState('failed'));
    }, [classic, state]);
    if (!classic || state === 'ready') return createElement(BlockEdit, props);
    return state === 'loading'
      ? createElement('p', { role: 'status' }, __('Loading the classic editor…', 'stillpress'))
      : createElement('p', { role: 'alert' }, __('Could not load the classic editor.', 'stillpress'), ' ',
        createElement('button', { type: 'button', onClick: () => setState('loading') }, __('Try again', 'stillpress')));
  };
addFilter('editor.BlockEdit', 'stillpress/classic-editor', withClassicEditor);

/** Loads TinyMCE and the globals core/freeform's edit modal reads; only classic items pay for the download. */
export function installClassicEditor(): Promise<void> {
  // a failed download is retried by the next call
  return installed ??= load().catch((e: unknown) => {
    installed = null;
    throw e;
  });
}

async function load(): Promise<void> {
  // tinymce.js sets window.tinymce itself; the theme and plugins resolve it as a global, so they come after
  const tinymce = (await import('tinymce')).default;
  await Promise.all([
    import('@wordpress/block-library/build-style/freeform/editor.css'),
    import('./classic.css'),
    import('tinymce/models/dom'),
    import('tinymce/themes/silver'),
    import('tinymce/icons/default'),
    import('tinymce/skins/ui/oxide/skin.js'),
    import('tinymce/skins/ui/oxide/content.js'),
    import('tinymce/skins/content/default/content.js'),
    import('tinymce/plugins/charmap'),
    import('tinymce/plugins/link'),
    import('tinymce/plugins/lists'),
    import('tinymce/plugins/media'),
    import('tinymce/plugins/fullscreen'),
    // TinyMCE's own UI strings (Tiny's community pack, under TinyMCE's licence); registered with addI18n, so the
    // editor never fetches langs/ru.js by URL
    locale() === 'ru' ? import('tinymce-i18n/langs8/ru.js') : null,
  ]);
  // freeform/modal: EditorManager.overrideDefaults({ base_url: wpEditorL10n.tinymce.baseURL, suffix }), then
  // initialize(id, { tinymce: { ...wpEditorL10n.tinymce.settings, setup } })
  window.wpEditorL10n = { tinymce: { baseURL: `${import.meta.env.BASE_URL}tinymce`, suffix: '.min', settings: SETTINGS } };
  window.wp = {
    ...window.wp,
    oldEditor: {
      // not called by core/freeform 12.1; kept because WordPress's wp.oldEditor has it
      getDefaultSettings: () => ({ tinymce: SETTINGS }),
      // freeform/modal ClassicEdit effect: initialize(`editor-${clientId}`, { tinymce }) on the modal's textarea
      initialize(id: string, { tinymce: own }: { tinymce: Record<string, unknown> }) {
        const target = document.getElementById(id);
        // the block's content already has its paragraphs (toBlocks); bodyOf removes them on save
        if (target) tinymce.init({ ...own, language: locale(), target });
      },
      // freeform/modal ClassicEdit effect cleanup: remove(id) when the modal closes
      remove: (id: string) => tinymce.get(id)?.remove(),
      // freeform/modal Save button: setAttributes({ content: getContent(id) })
      getContent: (id: string) => tinymce.get(id)?.getContent()
        ?? (document.getElementById(id) as HTMLTextAreaElement | null)?.value ?? '',
    },
  };
  ready = true;
}
