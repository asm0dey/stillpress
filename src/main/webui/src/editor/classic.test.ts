/// <reference types="bun" />
import { beforeAll, expect, test } from 'bun:test';
import { useDom, wxrBodies } from './testdom.ts';
import { Edits } from './edits.ts';

useDom();

let c: typeof import('./classic.ts');
let b: typeof import('@wordpress/blocks');
beforeAll(async () => {
  const { registerCoreBlocks } = await import('@wordpress/block-library');
  registerCoreBlocks();
  b = await import('@wordpress/blocks');
  c = await import('./classic.ts');
});

test('classic body opens as one freeform block, with paragraphs as WordPress parses it', () => {
  const blocks = c.toBlocks('<p>Hi</p>\n\nSecond', 'classic');
  expect(blocks.map((x) => x.name)).toEqual(['core/freeform']);
  expect(blocks[0].attributes.content).toBe('<p>Hi</p>\n<p>Second</p>');
});

test('format stays classic until a block is added or converted', () => {
  expect(c.bodyFormatOf(c.toBlocks('<p>Hi</p>', 'classic'))).toBe('classic');
  expect(c.bodyFormatOf([...c.toBlocks('<p>Hi</p>', 'classic'), b.createBlock('core/paragraph')])).toBe('blocks');
  expect(c.bodyFormatOf([])).toBe('blocks');
});

const opened = (body: string) => ({ serialized: b.serialize(c.toBlocks(body, 'classic')), body });

test('every unedited classic body of the sample WXR is stored back byte for byte', async () => {
  const classic = (await wxrBodies()).filter((body) => body && !body.includes('<!-- wp:'));
  expect(classic.length).toBeGreaterThan(0);
  expect(classic.map((body) => c.bodyOf(c.toBlocks(body, 'classic'), opened(body)))).toEqual(classic);
});

test('an edited Classic block is stored as raw classic HTML, paragraphs removed again', () => {
  const edited = [b.createBlock('core/freeform', { content: '<p>Line uno</p>\n<p>Line two</p>' })];
  expect(c.bodyOf(edited, opened('Line one\n\nLine two'))).toBe('Line uno\n\nLine two');
});

test('a block added to a classic post serializes with delimiters', () => {
  const blocks = [...c.toBlocks('Hi', 'classic'), b.createBlock('core/paragraph', { content: 'New' })];
  expect(c.bodyOf(blocks, opened('Hi'))).toBe('<p>Hi</p>\n\n<!-- wp:paragraph -->\n<p>New</p>\n<!-- /wp:paragraph -->');
});

test('opening an unedited classic post leaves it clean, so nothing is saved', async () => {
  const body = (await wxrBodies()).find((x) => x.includes('[caption'))!;
  const blocks = c.toBlocks(body, 'classic');
  const edits = new Edits(b.serialize(blocks));
  edits.blocks(b.serialize(blocks));
  expect([edits.dirty, edits.needsAutosave]).toEqual([false, false]);
});

test('a failed TinyMCE download is retried, not cached', async () => {
  // bun cannot load the skin CSS, so the install fails here as a lost chunk would in the browser
  const first = c.installClassicEditor();
  await expect(first).rejects.toBeDefined();
  const second = c.installClassicEditor();
  expect(second).not.toBe(first);
  await expect(second).rejects.toBeDefined();
});

test('TinyMCE keeps markup the user never touched', async () => {
  await import('tinymce');
  const t = (window as unknown as { tinymce: TinyHtml }).tinymce;
  const html = '<p>Hi <span style="color:red;font-weight:bold">x</span></p>'
    + '<iframe src="https://www.youtube.com/embed/x" width="560" height="315" frameborder="0" allowfullscreen=""></iframe>'
    + '<object data="a.swf"><param name="movie" value="a.swf" /><embed src="a.swf" type="application/x-shockwave-flash" /></object>'
    + '<script>var widget = 1;</script><div onclick="go()" data-x="1">d</div>';
  const schema = new t.html.Schema(c.SETTINGS);
  const parsed = new t.html.DomParser(c.SETTINGS, schema).parse(html);
  expect(new t.html.Serializer(c.SETTINGS, schema).serialize(parsed)).toBe(html);
});

interface TinyHtml {
  html: {
    Schema: new (s: object) => object;
    DomParser: new (s: object, schema: object) => { parse(html: string): object };
    Serializer: new (s: object, schema: object) => { serialize(node: object): string };
  };
}

test('a Classic block shows a placeholder until TinyMCE is installed; other blocks render as they are', async () => {
  const { createElement } = await import('react');
  const { renderToString } = await import('react-dom/server');
  const Original = ({ name }: { name: string }) => createElement('p', null, `edit ${name}`);
  const Filtered = c.withClassicEditor(Original);
  expect(renderToString(createElement(Filtered, { name: 'core/freeform' }))).toContain('Loading the classic editor…');
  expect(renderToString(createElement(Filtered, { name: 'core/paragraph' }))).toBe('<p>edit core/paragraph</p>');
});
