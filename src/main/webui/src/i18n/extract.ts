/** One translatable call: its Jed key (context + U+0004 + msgid for _x/_nx) and the text domain, if given. */
export interface Call { key: string; domain: string | undefined; plural: boolean }

// a quoted literal, or a template literal without interpolation (a ${…} id could not be looked up)
const S = String.raw`("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|\`(?:[^\`\\$]|\\.|\$(?!\{))*\`)`;
const C = String.raw`\s*,\s*`;
// the count argument of _n/_nx; shortcut: an expression with a comma inside parentheses is not matched
const N = String.raw`[^,]+?`;
const D = String.raw`(?:${C}${S})?\s*,?\s*\)`;
const CALLS: [RegExp, (m: RegExpExecArray) => Call][] = [
  [new RegExp(String.raw`\b__\(\s*${S}${D}`, 'g'), (m) => ({ key: str(m[1]), domain: opt(m[2]), plural: false })],
  [new RegExp(String.raw`\b_x\(\s*${S}${C}${S}${D}`, 'g'), (m) => ({ key: str(m[2]) + '\u0004' + str(m[1]), domain: opt(m[3]), plural: false })],
  [new RegExp(String.raw`\b_n\(\s*${S}${C}${S}${C}${N}${D}`, 'g'), (m) => ({ key: str(m[1]), domain: opt(m[3]), plural: true })],
  [new RegExp(String.raw`\b_nx\(\s*${S}${C}${S}${C}${N}${C}${S}${D}`, 'g'), (m) => ({ key: str(m[3]) + '\u0004' + str(m[1]), domain: opt(m[4]), plural: true })],
];

/** Every __, _x, _n and _nx call with literal arguments in a JS or TS source. */
export function extractCalls(source: string): Call[] {
  return CALLS.flatMap(([re, call]) => [...source.matchAll(re)].map((m) => call(m as RegExpExecArray)));
}

const opt = (lit: string | undefined) => (lit === undefined ? undefined : str(lit));

/** The value of a JS string literal (quotes included). */
function str(lit: string): string {
  return lit.slice(1, -1).replace(/\\(u\{[0-9a-fA-F]+\}|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|\r?\n|.)/gs, (_, e: string) => {
    if (e[0] === 'u' || e[0] === 'x') return String.fromCodePoint(parseInt(e.replace(/[ux{}]/g, ''), 16));
    return ({ n: '\n', t: '\t', r: '\r', b: '\b', f: '\f', v: '\v', '0': '\0' } as Record<string, string>)[e] ?? (e.trim() ? e : '');
  });
}
