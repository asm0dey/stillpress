/// <reference types="bun" />
import { afterAll, beforeAll, expect, test } from 'bun:test';
import { JSDOM } from 'jsdom';

// Block parsing needs a DOM. jsdom, not happy-dom: hpq reads attributes as named properties of NamedNodeMap
// (attributes.src), which happy-dom does not provide.
const added: string[] = [];
beforeAll(() => {
  const { window } = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
  const g = globalThis as Record<string, unknown>;
  for (const key of Object.getOwnPropertyNames(window)) {
    if (!(key in g)) {
      g[key] = (window as unknown as Record<string, unknown>)[key];
      added.push(key);
    }
  }
  g.window = window;
  g.document = window.document;
  added.push('window', 'document');
});
afterAll(() => added.forEach((k) => delete (globalThis as Record<string, unknown>)[k]));

const WXR = new URL('../../../../test/resources/wxr/sample-blog.xml', import.meta.url);

/** The sample export's block-format bodies: posts, pages and reusable blocks. */
async function blockBodies(): Promise<string[]> {
  const xml = await Bun.file(WXR).text();
  return [...xml.matchAll(/<content:encoded><!\[CDATA\[([\s\S]*?)\]\]><\/content:encoded>/g)]
    .map((m) => m[1].replaceAll(']]]]><![CDATA[>', ']]>'))
    .filter((body) => body.includes('<!-- wp:'));
}

interface Parsed { name: string; isValid: boolean; innerBlocks: Parsed[] }
const flatten = (blocks: Parsed[]): Parsed[] => blocks.flatMap((b) => [b, ...flatten(b.innerBlocks)]);

test('every block in the sample WXR is valid Gutenberg content', async () => {
  const { registerCoreBlocks } = await import('@wordpress/block-library');
  const { parse } = await import('@wordpress/blocks');
  registerCoreBlocks();
  const bodies = await blockBodies();
  expect(bodies.length).toBeGreaterThan(0);
  const blocks = bodies.flatMap((body) => flatten(parse(body) as unknown as Parsed[]));
  expect(blocks.map((b) => b.name)).toContain('core/gallery');
  expect(blocks.filter((b) => !b.isValid).map((b) => b.name)).toEqual([]);
});
