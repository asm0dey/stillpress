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
