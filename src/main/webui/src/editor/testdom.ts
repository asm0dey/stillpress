/// <reference types="bun" />
import { afterAll, beforeAll } from 'bun:test';
import { JSDOM } from 'jsdom';

/**
 * Block parsing needs a DOM. jsdom, not happy-dom: hpq reads attributes as named properties of NamedNodeMap
 * (attributes.src), which happy-dom does not provide. Import the block packages after this runs (dynamic import).
 */
export function useDom(): void {
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
}

const WXR = new URL('../../../../test/resources/wxr/sample-blog.xml', import.meta.url);

/** The bodies of every item in the sample export. */
export async function wxrBodies(): Promise<string[]> {
  const xml = await Bun.file(WXR).text();
  return [...xml.matchAll(/<content:encoded><!\[CDATA\[([\s\S]*?)\]\]><\/content:encoded>/g)]
    .map((m) => m[1].replaceAll(']]]]><![CDATA[>', ']]>'));
}
