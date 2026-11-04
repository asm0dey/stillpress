/**
 * Filters WordPress's Russian Jed export down to the strings the bundled @wordpress/* packages use.
 * Usage: bun scripts/filter-wp-ru.ts <full export.json> <out.json>
 * Kept: every msgid of a literal __/_x/_n/_nx call in node_modules/@wordpress/<pkg>/build-module/**.mjs, plus the
 * block.json fields WordPress translates at block registration (@wordpress/blocks i18n-block.json).
 * Exits 1 when the result misses an obvious editor string.
 */
import { Glob } from 'bun';
import { readFileSync, writeFileSync } from 'node:fs';
import { extractCalls } from '../src/i18n/extract.ts';

const [input, output] = process.argv.slice(2);
if (!input || !output) throw new Error('usage: bun scripts/filter-wp-ru.ts <export.json> <out.json>');
const root = new URL('../node_modules/@wordpress/', import.meta.url).pathname;
const keys = new Set<string>();
for (const f of new Glob('*/build-module/**/*.mjs').scanSync(root)) {
  for (const c of extractCalls(readFileSync(root + f, 'utf8'))) keys.add(c.key);
}
interface BlockJson {
  title?: string; description?: string; keywords?: string[]; styles?: { label?: string }[];
  variations?: { title?: string; description?: string; keywords?: string[] }[];
}
const add = (context: string, text: string | undefined) => text && keys.add(context + '\u0004' + text);
for (const f of new Glob('*/build-module/**/block.json').scanSync(root)) {
  const b: BlockJson = JSON.parse(readFileSync(root + f, 'utf8'));
  add('block title', b.title);
  add('block description', b.description);
  b.keywords?.forEach((k) => add('block keyword', k));
  b.styles?.forEach((s) => add('block style label', s.label));
  b.variations?.forEach((v) => {
    add('block variation title', v.title);
    add('block variation description', v.description);
    v.keywords?.forEach((k) => add('block variation keyword', k));
  });
}

const full = JSON.parse(readFileSync(input, 'utf8'));
const messages: Record<string, unknown> = full.locale_data.messages;
const kept = Object.fromEntries(Object.entries(messages).filter(([k]) => k === '' || keys.has(k)));
const missing = ['Add block', 'Publish', 'Block', 'block title\u0004Paragraph'].filter((k) => !(k in kept));
if (missing.length) {
  console.error('filtered snapshot misses', missing);
  process.exit(1);
}
writeFileSync(output, JSON.stringify({ ...full, locale_data: { messages: kept } }) + '\n');
console.log(`kept ${Object.keys(kept).length - 1} of ${Object.keys(messages).length - 1} entries`);
