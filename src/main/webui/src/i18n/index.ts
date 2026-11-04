import { setLocaleData } from '@wordpress/i18n';

/** The admin languages. */
export type Locale = 'en' | 'ru';

let current: Locale = 'en';

/** The language the admin is showing. */
export const locale = (): Locale => current;

/** Before any session exists (setup, login): the browser's language. */
export const browserLocale = (): Locale => (navigator.language.toLowerCase().startsWith('ru') ? 'ru' : 'en');

/** Our strings (text domain stillpress) for {@code l}; English needs nothing. Also sets {@code <html lang>}. */
export async function loadLocale(l: Locale): Promise<void> {
  if (l === 'ru') setLocaleData((await import('./ru.json')).default, 'stillpress');
  current = l;
  document.documentElement.lang = l;
}

/**
 * Gutenberg's own strings, from WordPress's translation. The editor chunks load it before Gutenberg's modules run,
 * because blocks translate their names when they register.
 */
export async function loadWordPressLocale(): Promise<void> {
  if (current === 'ru') setLocaleData((await import('./wp-ru.json')).default.locale_data.messages, 'default');
}

const format = (d: Date, timeZone: string): string =>
  new Intl.DateTimeFormat(current, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(d);

/** A server timestamp (UTC, with or without a zone suffix) in the admin's language and the site's time zone. */
export const formatInstant = (s: string, timeZone: string): string =>
  format(new Date(/(Z|[+-]\d\d:?\d\d)$/i.test(s) ? s : s + 'Z'), timeZone);

/** A wall-clock date and time already in the site's time zone (an item's date), in the admin's language. */
export const formatLocal = (s: string): string => format(new Date(s + 'Z'), 'UTC');

const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/**
 * The @wordpress/date l10n block for the admin's language, from the platform's own names (WordPress hands these over
 * from PHP): month and weekday names for the date picker, and the week's first day.
 */
export function dateL10n() {
  const names = (opts: Intl.DateTimeFormatOptions, count: number, at: (i: number) => Date) =>
    Array.from({ length: count }, (_, i) => capital(new Intl.DateTimeFormat(current, { ...opts, timeZone: 'UTC' }).format(at(i))));
  const month = (i: number) => new Date(Date.UTC(2024, i, 1));
  // 2024-01-07 was a Sunday; WordPress lists weekdays from Sunday
  const day = (i: number) => new Date(Date.UTC(2024, 0, 7 + i));
  return {
    locale: current,
    months: names({ month: 'long' }, 12, month),
    monthsShort: names({ month: 'short' }, 12, month),
    weekdays: names({ weekday: 'long' }, 7, day),
    weekdaysShort: names({ weekday: 'short' }, 7, day),
    startOfWeek: (current === 'ru' ? 1 : 0) as 0 | 1,
  };
}
