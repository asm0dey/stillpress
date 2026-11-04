import { readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// not new URL(…, import.meta.url): Vite rewrites that pattern as an asset reference
const WXR = join(dirname(fileURLToPath(import.meta.url)), '../../../../test/resources/wxr/sample-blog.xml');

/** The bodies of every item in the sample export. */
export async function wxrBodies(): Promise<string[]> {
  const xml = await readFile(WXR, 'utf8');
  return [...xml.matchAll(/<content:encoded><!\[CDATA\[([\s\S]*?)\]\]><\/content:encoded>/g)]
    .map((m) => m[1].replaceAll(']]]]><![CDATA[>', ']]>'));
}
