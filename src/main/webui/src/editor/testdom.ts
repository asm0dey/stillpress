/// <reference types="bun" />
import { JSDOM } from 'jsdom';

/**
 * The tests' DOM, installed once by bunfig.toml's preload: React, Testing Library and the block packages look for it
 * when they load, and Stryker's runner loads every mutated module before any test runs. jsdom, not happy-dom: hpq
 * reads attributes as named properties of NamedNodeMap (attributes.src), which happy-dom does not provide. Bun's own
 * globals (fetch, navigator, URL…) stay.
 */
const { window } = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
const g = globalThis as Record<string, unknown>;
for (const key of Object.getOwnPropertyNames(window)) {
  if (!(key in g)) g[key] = (window as unknown as Record<string, unknown>)[key];
}
g.window = window;
g.document = window.document;

const WXR = new URL('../../../../test/resources/wxr/sample-blog.xml', import.meta.url);

/** The bodies of every item in the sample export. */
export async function wxrBodies(): Promise<string[]> {
  const xml = await Bun.file(WXR).text();
  return [...xml.matchAll(/<content:encoded><!\[CDATA\[([\s\S]*?)\]\]><\/content:encoded>/g)]
    .map((m) => m[1].replaceAll(']]]]><![CDATA[>', ']]>'));
}
