import {
  type Block as BlockInstance, __unstableSerializeAndClean, createBlock, parse, serialize, setFreeformContentHandlerName,
} from '@wordpress/blocks';
import { autop } from '@wordpress/autop';
import type { TinyMCE } from 'tinymce';

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
  skin: false,
  menubar: false,
  branding: false,
  plugins: 'charmap colorpicker hr link lists media paste tabfocus textcolor fullscreen',
  toolbar1: 'formatselect bold italic bullist numlist blockquote alignleft aligncenter alignright link unlink fullscreen',
  toolbar2: 'strikethrough hr forecolor pastetext removeformat charmap outdent indent undo redo',
  relative_urls: false,
  convert_urls: false,
  entity_encoding: 'raw',
  // old posts carry iframes, embeds, scripts and event attributes; TinyMCE's schema would drop what the user never
  // touched (inside the editor TinyMCE keeps scripts inert)
  valid_elements: '*[*]',
};

declare global {
  interface Window {
    tinymce?: TinyMCE;
    wpEditorL10n?: { tinymce: { baseURL: string; suffix: string; settings: Record<string, unknown> } };
    wp?: { oldEditor?: unknown };
  }
}

let installed: Promise<void> | null = null;

/** Loads TinyMCE 4 and the globals core/freeform's edit modal reads; only classic items pay for the download. */
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
  const [{ default: contentCss }] = await Promise.all([
    import('tinymce/skins/lightgray/content.min.css?inline'),
    // skin: false plus the bundled skin CSS: Vite hashes its fonts, nothing is fetched from a TinyMCE base URL
    import('tinymce/skins/lightgray/skin.min.css'),
    import('@wordpress/block-library/build-style/freeform/editor.css'),
    import('./classic.css'),
    import('tinymce/themes/modern/theme'),
    import('tinymce/plugins/charmap/plugin'),
    import('tinymce/plugins/colorpicker/plugin'),
    import('tinymce/plugins/hr/plugin'),
    import('tinymce/plugins/link/plugin'),
    import('tinymce/plugins/lists/plugin'),
    import('tinymce/plugins/media/plugin'),
    import('tinymce/plugins/paste/plugin'),
    import('tinymce/plugins/tabfocus/plugin'),
    import('tinymce/plugins/textcolor/plugin'),
    import('tinymce/plugins/fullscreen/plugin'),
  ]) as [{ default: string }, ...unknown[]];
  const settings = { ...SETTINGS, content_style: contentCss };
  // freeform/modal: EditorManager.overrideDefaults({ base_url: wpEditorL10n.tinymce.baseURL, suffix }), then
  // initialize(id, { tinymce: { ...wpEditorL10n.tinymce.settings, setup } })
  window.wpEditorL10n = { tinymce: { baseURL: `${import.meta.env.BASE_URL}tinymce`, suffix: '.min', settings } };
  window.wp = {
    ...window.wp,
    oldEditor: {
      // not called by core/freeform 12.1; kept because WordPress's wp.oldEditor has it
      getDefaultSettings: () => ({ tinymce: settings }),
      // freeform/modal ClassicEdit effect: initialize(`editor-${clientId}`, { tinymce }) on the modal's textarea
      initialize(id: string, { tinymce: own }: { tinymce: Record<string, unknown> }) {
        const target = document.getElementById(id);
        // the block's content already has its paragraphs (toBlocks); bodyOf removes them on save
        if (target) tinymce.init({ ...own, target });
      },
      // freeform/modal ClassicEdit effect cleanup: remove(id) when the modal closes
      remove: (id: string) => tinymce.get(id)?.remove(),
      // freeform/modal Save button: setAttributes({ content: getContent(id) })
      getContent: (id: string) => tinymce.get(id)?.getContent()
        ?? (document.getElementById(id) as HTMLTextAreaElement | null)?.value ?? '',
    },
  };
}
