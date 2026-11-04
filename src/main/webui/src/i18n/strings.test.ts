import { describe, expect, test } from 'vitest';
import { globSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { extractCalls } from './extract.ts';
import ru from './ru.json';

/*
 * Every user-visible string of ours goes through a literal __/_x/_n/_nx call with the 'stillpress' domain, so this
 * scan sees all of them. Exceptions (strings that reach __ through a variable), with the reason:
 * none.
 */
// not new URL('..', import.meta.url): Vite rewrites that pattern as an asset reference
const src = join(dirname(fileURLToPath(import.meta.url)), '../');
const calls = globSync('**/*.{ts,tsx}', { cwd: src })
  .filter((f) => !/\.test\.tsx?$/.test(f) && !f.startsWith('i18n/'))
  .flatMap((f) => extractCalls(readFileSync(src + f, 'utf8')).map((c) => ({ ...c, file: f })));
const keys = new Set(calls.map((c) => c.key));
const entries = Object.keys(ru).filter((k) => k !== '');

describe('stillpress strings', () => {
  test('the scan finds our strings', () => {
    expect(keys.size).toBeGreaterThan(100);
  });
  test('every call names the stillpress domain', () => {
    expect(calls.filter((c) => c.domain !== 'stillpress').map((c) => `${c.file}: ${c.key}`)).toEqual([]);
  });
  test('every stillpress string has a Russian translation', () => {
    expect([...keys].filter((k) => !(k in ru))).toEqual([]);
  });
  test('no Russian entry is orphaned', () => {
    expect(entries.filter((k) => !keys.has(k))).toEqual([]);
  });
  test('plural strings have the three Russian forms, others one', () => {
    const plural = new Set(calls.filter((c) => c.plural).map((c) => c.key));
    const ruForms = ru as unknown as Record<string, string[]>;
    expect(entries.filter((k) => ruForms[k].length !== (plural.has(k) ? 3 : 1) || ruForms[k].some((t) => !t))).toEqual([]);
  });
});

describe('extractCalls', () => {
  test('reads each call shape and its domain', () => {
    expect(extractCalls(`__('A', 'stillpress'); __(\`Menu: "%s"\`); __(\`\${skipped}\`); _x("B", "ctx", 'stillpress'); _n('%d item', '%d items', list.length, 'stillpress');
      _nx('one', 'many', n, 'noun', 'stillpress'); __("It\\'s"); sprintf(__('%s left', 'stillpress'), x)`)).toEqual([
      { key: 'A', domain: 'stillpress', plural: false },
      { key: 'Menu: "%s"', domain: undefined, plural: false },
      { key: "It's", domain: undefined, plural: false },
      { key: '%s left', domain: 'stillpress', plural: false },
      { key: 'ctx\u0004B', domain: 'stillpress', plural: false },
      { key: '%d item', domain: 'stillpress', plural: true },
      { key: 'noun\u0004one', domain: 'stillpress', plural: true },
    ]);
  });
});
