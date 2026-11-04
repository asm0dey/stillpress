import { expect, test } from 'bun:test';
import { __ } from '@wordpress/i18n';
import { loadLocale, zoneOffset } from './index.ts';

test('English after Russian shows English again', async () => {
  await loadLocale('ru');
  expect(__('Posts', 'stillpress') as string).toBe('Записи');
  await loadLocale('en');
  expect(__('Posts', 'stillpress') as string).toBe('Posts');
  expect(document.documentElement.lang).toBe('en');
});

test('zoneOffset follows the zone and the date', () => {
  const winter = new Date(Date.UTC(2026, 0, 15));
  expect(zoneOffset('Europe/Moscow', winter)).toEqual({ offset: 3, offsetFormatted: '3', abbr: 'UTC+3' });
  expect(zoneOffset('Asia/Kolkata', winter)).toEqual({ offset: 5.5, offsetFormatted: '5:30', abbr: 'UTC+5:30' });
  expect(zoneOffset('America/St_Johns', winter)).toEqual({ offset: -3.5, offsetFormatted: '-3:30', abbr: 'UTC-3:30' });
  expect(zoneOffset('UTC', winter)).toEqual({ offset: 0, offsetFormatted: '0', abbr: 'UTC+0' });
  expect(zoneOffset('Europe/Berlin', new Date(Date.UTC(2026, 6, 15))).offset).toBe(2);
});
